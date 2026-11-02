package app;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static app.PublicationCrashTest.fields;
import static org.junit.jupiter.api.Assertions.*;

class DemoResetCrashTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"reset-intent", "reset-git", "reset-finalized"})
    @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void resetRecoversAcrossActualJvmHalt(String stage) throws Exception {
        Path data = temporary.resolve("demo");
        Path sentinel = temporary.resolve("outside-demo.txt");
        Files.writeString(sentinel, "keep sibling data");
        Path root = Path.of(System.getProperty("sysml.root")).toAbsolutePath();
        // The reusable process fixture selects LocalServer's default reference.
        Path fixture = root.resolve("fixtures/water-tank.sysml");
        String reference = Files.readString(fixture);
        String operation = UUID.randomUUID().toString();
        String shared = UUID.randomUUID().toString();
        WorkspaceService.DemoState original;
        String deliveredHead;
        JsonObject reset;

        try (var child = PublicationCrashTest.ServerProcess.start(data, stage)) {
            original = demo(child);
            assertEquals(reference, original.alice().source().text());
            assertEquals(reference, original.bob().source().text());
            assertEquals(original.alice().acceptedBaseRevision(), original.bob().acceptedBaseRevision());

            String aliceText = reference.replace("= 10;", "= 12;");
            assertNotEquals(reference, aliceText);
            var alice = child.action(original.alice(), "save", "text", aliceText).state();
            alice = child.action(alice, "record", "description", "Discard Alice flow 12").state();
            alice = child.action(alice, "share", "operationId", shared, "description", "Discard shared draft").state();
            var delivered = child.action(alice, "deliver", "operationId", UUID.randomUUID());
            assertEquals("ok", delivered.status());
            alice = delivered.state();
            deliveredHead = alice.acceptedHead();
            assertNotEquals(original.alice().acceptedHead(), deliveredHead);
            assertEquals(aliceText, alice.source().text());
            assertEquals(1, alice.history().size());
            assertTrue(alice.history().getFirst().accepted());
            assertNotNull(child.get("/api/drafts/" + shared));

            String bobText = reference.replace("= 10;", "= 15;");
            assertNotEquals(reference, bobText);
            var bob = child.action(original.bob(), "save", "text", bobText).state();
            bob = child.action(bob, "record", "description", "Discard Bob conflict").state();
            var conflicted = child.action(bob, "deliver", "operationId", UUID.randomUUID());
            assertEquals("needs-resolution", conflicted.status());
            assertEquals(1, conflicted.state().conflicts().size());
            assertEquals(deliveredHead, conflicted.state().acceptedHead());

            reset = fields("expectedGeneration", original.generation(), "operationId", operation);
            assertThrows(IOException.class, () -> child.post("/api/demo/reset", reset));
            assertTrue(child.process.waitFor(20, TimeUnit.SECONDS), "Reset child did not exit: " + child.output);
            assertEquals(86, child.process.exitValue(), child.output.toString());
        }

        // Inspect the durable boundary before any service can recover it.
        DemoLifecycle.Info intended;
        String intendedRoot;
        try (var store = new WorkspaceStore(data.resolve("workspaces.sqlite"))) {
            var intent = WorkspaceService.JSON.fromJson(store.metadata("reset"), DemoLifecycle.ResetIntent.class);
            if (stage.equals("reset-finalized")) {
                assertNull(intent);
                intended = WorkspaceService.JSON.fromJson(store.metadata("demo"), DemoLifecycle.Info.class);
                intendedRoot = WorkspaceService.JSON.fromJson(store.get(intended.aliceWorkspaceId()).json(), WorkspaceData.class).baseCommit();
            } else {
                assertNotNull(intent);
                assertEquals(original.generation(), intent.expectedGeneration());
                intended = intent.info();
                intendedRoot = intent.rootCommit();
                assertNotNull(store.get(original.alice().workspaceId()));
                assertNotNull(store.get(original.bob().workspaceId()));
            }
            assertEquals(operation, intended.lastResetOperation());
        }
        var git = new GitStore(data.resolve("project.git"));
        assertEquals(stage.equals("reset-intent") ? deliveredHead : intendedRoot, git.head(GitStore.MAIN));
        assertEquals(stage.equals("reset-intent") ? List.of("refs/drafts/" + shared) : List.of(), git.draftRefs());
        assertEquals("keep sibling data", Files.readString(sentinel));

        WorkspaceService.DemoState recovered;
        try (var child = PublicationCrashTest.ServerProcess.start(data, null)) {
            recovered = demo(child);
            assertEquals(intended.generation(), recovered.generation());
            assertEquals(intended.aliceWorkspaceId(), recovered.alice().workspaceId());
            assertEquals(intended.bobWorkspaceId(), recovered.bob().workspaceId());
            assertNotEquals(original.generation(), recovered.generation());
            var retiredIds = Set.of(original.alice().workspaceId(), original.bob().workspaceId());
            assertFalse(retiredIds.contains(recovered.alice().workspaceId()));
            assertFalse(retiredIds.contains(recovered.bob().workspaceId()));
            assertFresh(recovered, reference, intendedRoot);
            assertProject(child, intendedRoot);
            // A retry carries the OLD generation and must not create another pair/root.
            child.post("/api/demo/reset", reset);
            assertEquals(recovered, demo(child));
        }
        try (var child = PublicationCrashTest.ServerProcess.start(data, null)) {
            assertEquals(recovered, demo(child));
            child.post("/api/demo/reset", reset);
            assertEquals(recovered, demo(child));
            assertProject(child, intendedRoot);
        }

        // The reusable HTTP helper requires 200, so inspect retired IDs through the service after closing children.
        try (var service = new WorkspaceService(data, Path.of(System.getProperty("sysml.pilot.library")), fixture)) {
            assertEquals(404, assertThrows(Problem.class, () -> service.state(original.alice().workspaceId())).http);
            assertEquals(404, assertThrows(Problem.class, () -> service.state(original.bob().workspaceId())).http);
            assertEquals(404, assertThrows(Problem.class, () -> service.draft(shared)).http);
            assertEquals(404, assertThrows(Problem.class, () -> service.execute(original.bob().workspaceId(), "save",
                    PublicationCrashTest.body(original.bob(), "text", "retired request"))).http);
            assertEquals(409, assertThrows(Problem.class,
                    () -> service.resetDemo(original.generation(), UUID.randomUUID().toString())).http);
            assertEquals(recovered, service.demo());
        }
        try (var store = new WorkspaceStore(data.resolve("workspaces.sqlite"))) {
            assertEquals(Set.of(recovered.alice().workspaceId(), recovered.bob().workspaceId()),
                    store.all().stream().map(WorkspaceStore.Row::id).collect(java.util.stream.Collectors.toSet()));
            assertNull(store.metadata("reset"));
            assertTrue(store.pending().isEmpty());
        }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workspaces.sqlite").toAbsolutePath());
             var statement = db.createStatement()) {
            for (String table : List.of("workspaces", "revisions", "operations", "intents")) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(table.equals("workspaces") ? 2 : 0, rows.getInt(1), table);
                }
            }
        }
        assertEquals(intendedRoot, git.head(GitStore.MAIN));
        assertTrue(git.draftRefs().isEmpty());
        var history = git.acceptedHistory();
        assertEquals(1, history.size());
        assertEquals(intendedRoot, history.getFirst().revisionId());
        assertNull(history.getFirst().parentRevisionId());
        assertEquals(reference, git.read(intendedRoot, "model.sysml"));
        assertEquals("keep sibling data", Files.readString(sentinel));
    }

    private static WorkspaceService.DemoState demo(PublicationCrashTest.ServerProcess child) throws Exception {
        return WorkspaceService.JSON.fromJson(child.get("/api/demo"), WorkspaceService.DemoState.class);
    }

    private static void assertFresh(WorkspaceService.DemoState demo, String reference, String head) {
        assertNotEquals(demo.alice().workspaceId(), demo.bob().workspaceId());
        assertEquals("Alice", demo.alice().actor());
        assertEquals("Bob", demo.bob().actor());
        for (var state : List.of(demo.alice(), demo.bob())) {
            assertEquals(reference, state.source().text());
            assertEquals(0, state.revision());
            assertEquals(0, state.source().version());
            assertEquals(head, state.acceptedBaseRevision());
            assertEquals(head, state.acceptedHead());
            assertEquals(ModelContracts.AnalysisState.VALID, state.analysis().state());
            assertFalse(state.dirty());
            assertTrue(state.history().isEmpty());
            assertTrue(state.conflicts().isEmpty());
        }
    }

    private static void assertProject(PublicationCrashTest.ServerProcess child, String head) throws Exception {
        var project = WorkspaceService.JSON.fromJson(child.get("/api/project"), JsonObject.class);
        assertEquals(head, project.get("acceptedHead").getAsString());
        assertEquals(2, project.getAsJsonArray("workspaces").size());
        assertEquals(1, project.getAsJsonArray("acceptedHistory").size());
        assertTrue(project.getAsJsonArray("drafts").isEmpty());
    }
}
