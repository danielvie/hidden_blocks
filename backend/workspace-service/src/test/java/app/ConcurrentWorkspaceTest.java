package app;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static app.PublicationCrashTest.body;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentWorkspaceTest {
    @TempDir Path data;

    private WorkspaceService service() throws Exception {
        return new WorkspaceService(data, Path.of(System.getProperty("sysml.pilot.library")),
                Path.of(System.getProperty("sysml.root"), "fixtures/pump.sysml"));
    }

    private static ModelContracts.Attribute flow(WorkspaceData.State state) {
        return state.analysis().elements().stream().flatMap(e -> e.attributes().stream())
                .filter(a -> a.name().equals("flowRate")).findFirst().orElseThrow();
    }

    private static WorkspaceData.State action(WorkspaceService service, WorkspaceData.State state,
                                               String action, Object... values) throws Exception {
        return service.execute(state.workspaceId(), action, body(state, values)).state();
    }

    @Test
    @Timeout(90)
    void simultaneousDeliveriesPreserveBothChangesAndRejectStaleSavesAndResolutions() throws Exception {
        WorkspaceData.State winner, resolved;
        String initialHead;
        try (var service = service()) {
            var alice = service.create("Alice").state();
            var bob = service.create("Bob").state();
            assertNotEquals(alice.workspaceId(), bob.workspaceId());
            assertEquals(alice.acceptedBaseRevision(), bob.acceptedBaseRevision());
            initialHead = alice.acceptedHead();
            alice = action(service, alice, "edit", "attributeId", flow(alice).id(), "expression", "12");
            alice = action(service, alice, "record", "description", "Alice keeps 12");
            bob = action(service, bob, "edit", "attributeId", flow(bob).id(), "expression", "15");
            bob = action(service, bob, "record", "description", "Bob keeps 15");
            var authored = List.of(alice, bob);
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            List<WorkspaceData.Response> deliveries;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = authored.stream().map(state -> executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "Concurrent delivery start was not released");
                    return service.execute(state.workspaceId(), "deliver", body(state, "operationId", UUID.randomUUID()));
                })).toList();
                try {
                    assertTrue(ready.await(10, TimeUnit.SECONDS), "Both delivery callers must be ready");
                } finally {
                    start.countDown();
                }
                deliveries = List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, deliveries.stream().filter(r -> r.status().equals("ok")).count());
            assertEquals(1, deliveries.stream().filter(r -> r.status().equals("needs-resolution")).count());
            winner = deliveries.stream().filter(r -> r.status().equals("ok")).findFirst().orElseThrow().state();
            var loser = deliveries.stream().filter(r -> r.status().equals("needs-resolution")).findFirst().orElseThrow().state();
            var originalLoser = authored.stream().filter(s -> s.workspaceId().equals(loser.workspaceId())).findFirst().orElseThrow();
            assertEquals(winner.acceptedHead(), loser.acceptedHead());
            assertNotEquals(initialHead, winner.acceptedHead());
            var git = new GitStore(data.resolve("project.git"));
            assertEquals(winner.source().text(), git.read(winner.acceptedHead(), "model.sysml"));
            assertTrue(git.ancestor(initialHead, winner.acceptedHead()));
            assertEquals(originalLoser.source().text(), loser.source().text());
            assertEquals(originalLoser.history().getFirst().changeId(), loser.history().getFirst().changeId());
            assertEquals(1, loser.conflicts().size());
            var conflict = loser.conflicts().getFirst();
            assertEquals("10", conflict.base().value().scalar().canonical());
            assertEquals(flow(winner).value().scalar().canonical(), conflict.incoming().value().scalar().canonical());
            assertEquals(flow(originalLoser).value().scalar().canonical(), conflict.local().value().scalar().canonical());
            assertEquals(winner.actor(), conflict.incoming().actor());

            var staleSave = body(originalLoser, "text", "package Lost {}");
            Problem saveProblem = assertThrows(Problem.class, () -> service.execute(loser.workspaceId(), "save", staleSave));
            assertEquals(409, saveProblem.http);
            assertEquals("stale", saveProblem.status);
            assertEquals(loser, service.state(loser.workspaceId()));

            JsonObject resolution = body(loser, "originChangeId", conflict.originChangeId(),
                    "conflictId", conflict.id(), "expression", "14");
            // A new recorded edit advances the workspace while leaving the conflict unresolved.
            var descendant = action(service, loser, "add", "elementId", flow(loser).ownerId(),
                    "name", "alertEnabled", "typeName", "Boolean", "expression", "true");
            descendant = action(service, descendant, "record", "description", "Keep descendant while conflicted");
            Problem resolveProblem = assertThrows(Problem.class, () -> service.execute(loser.workspaceId(), "resolve", resolution));
            assertEquals(409, resolveProblem.http);
            assertEquals("stale", resolveProblem.status);
            assertEquals(descendant, service.state(loser.workspaceId()));
            assertEquals(winner.acceptedHead(), git.head(GitStore.MAIN));
            assertEquals(winner, service.state(winner.workspaceId()));
            resolved = action(service, descendant, "resolve", "originChangeId", conflict.originChangeId(),
                    "conflictId", conflict.id(), "expression", "14");
            assertTrue(resolved.conflicts().isEmpty());
            assertEquals("14", flow(resolved).value().scalar().canonical());
            assertTrue(resolved.source().text().contains("alertEnabled"));
            assertEquals(2, resolved.history().size());
            assertEquals(winner.acceptedHead(), git.head(GitStore.MAIN));
        }
        try (var service = service()) {
            assertEquals(winner, service.state(winner.workspaceId()));
            assertEquals(resolved, service.state(resolved.workspaceId()));
            assertEquals(winner.acceptedHead(), service.project().get("acceptedHead"));
        }
    }
}
