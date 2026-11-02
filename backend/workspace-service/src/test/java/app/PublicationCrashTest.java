package app;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PublicationCrashTest {
    @TempDir Path data;

    @ParameterizedTest
    @ValueSource(strings = {"candidate", "intent", "git", "finalized"})
    @Timeout(120)
    void deliveryRecoversAcrossActualJvmHalt(String stage) throws Exception {
        recoverPublication(stage, "deliver");
    }

    @ParameterizedTest
    @ValueSource(strings = {"candidate", "intent", "git", "finalized"})
    @Timeout(120)
    void sharedDraftRecoversWithoutChangingAcceptedHistory(String stage) throws Exception {
        recoverPublication(stage, "share");
    }

    private void recoverPublication(String stage, String action) throws Exception {
        String operation = UUID.randomUUID().toString();
        WorkspaceData.State recorded;
        JsonObject request;
        String originalText;
        try (var child = ServerProcess.start(data, stage)) {
            var state = child.post("/api/workspaces", fields("actor", "Crash test")).state();
            originalText = state.source().text().replace("= 10;", "= 12;");
            assertNotEquals(state.source().text(), originalText);
            state = child.action(state, "save", "text", originalText).state();
            recorded = child.action(state, "record", "description", "Preserve flow 12").state();
            assertEquals(1, recorded.history().size());
            request = body(recorded, "operationId", operation, "description", "Crash recovery draft");
            assertThrows(IOException.class, () -> child.post(route(recorded, action), request));
            assertTrue(child.process.waitFor(20, TimeUnit.SECONDS), "Publication child did not exit");
            assertEquals(86, child.process.exitValue(), child.output.toString());
        }

        // Recovery coordinates two independent stores; this does not assert a cross-store transaction.
        try (var child = ServerProcess.start(data, null)) {
            var beforeRetry = child.state(recorded.workspaceId());
            assertEquals(originalText, beforeRetry.source().text());
            assertEquals(recorded.revision() + (stage.equals("candidate") ? 0 : 1), beforeRetry.revision());
            var result = child.post(route(recorded, action), request);
            assertEquals("ok", result.status());
            var finalized = child.state(recorded.workspaceId());
            assertEquals(recorded.revision() + 1, finalized.revision());
            assertEquals(originalText, finalized.source().text());
            assertFalse(finalized.dirty());
            assertTrue(finalized.conflicts().isEmpty());
            assertEquals(1, finalized.history().size());
            assertEquals(recorded.history().getFirst().changeId(), finalized.history().getFirst().changeId());
            assertEquals("Preserve flow 12", finalized.history().getFirst().description());
            assertEquals(originalText, finalized.history().getFirst().text());
            assertEquals(finalized, child.post(route(recorded, action), request).state());
            assertEquals(finalized, child.state(recorded.workspaceId()));

            var git = new GitStore(data.resolve("project.git"));
            if (action.equals("deliver")) {
                assertNotEquals(recorded.acceptedHead(), finalized.acceptedHead());
                assertEquals(finalized.acceptedHead(), finalized.acceptedBaseRevision());
                assertEquals(originalText, git.read(finalized.acceptedHead(), "model.sysml"));
                assertEquals(recorded.acceptedHead(), gitOutput(data, "rev-parse", finalized.acceptedHead() + "^"));
                assertEquals("1", gitOutput(data, "rev-list", "--count", recorded.acceptedHead() + ".." + finalized.acceptedHead()));
            } else {
                assertEquals(recorded.acceptedHead(), finalized.acceptedHead());
                assertEquals(recorded.acceptedBaseRevision(), finalized.acceptedBaseRevision());
                var draft = WorkspaceService.JSON.fromJson(child.get("/api/drafts/" + operation), WorkspaceService.SharedDraft.class);
                assertEquals(finalized, draft.state());
                assertEquals(List.of("refs/drafts/" + operation), git.draftRefs());
                assertEquals(originalText, git.read(git.head("refs/drafts/" + operation), "model.sysml"));
            }
        }
        try (var store = new WorkspaceStore(data.resolve("workspaces.sqlite"))) {
            assertTrue(store.pending().isEmpty());
            assertNotNull(store.operation(operation, recorded.workspaceId(), action));
            var persisted = WorkspaceService.JSON.fromJson(store.get(recorded.workspaceId()).json(), WorkspaceData.class);
            assertEquals(persisted.revision(), store.get(recorded.workspaceId()).revision());
            assertEquals(action.equals("deliver") ? 1 : 0, persisted.deliveredCount());
            assertNotNull(store.revision(persisted.history().getFirst().change().revisionId()));
        }
    }

    private static String gitOutput(Path data, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "--git-dir=" + data.resolve("project.git")));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Git inspection timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    static JsonObject fields(Object... values) {
        var json = new JsonObject();
        for (int i = 0; i < values.length; i += 2) {
            if (values[i + 1] instanceof Number number) json.addProperty(values[i].toString(), number);
            else json.addProperty(values[i].toString(), values[i + 1].toString());
        }
        return json;
    }

    static JsonObject body(WorkspaceData.State state, Object... values) {
        var json = fields(values);
        json.addProperty("expectedRevision", state.revision());
        json.addProperty("documentVersion", state.source().version());
        return json;
    }

    static String route(WorkspaceData.State state, String action) {
        return "/api/workspaces/" + state.workspaceId() + "/" + action;
    }

    static final class ServerProcess implements AutoCloseable {
        final Process process;
        final StringBuffer output = new StringBuffer();
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        private final Thread reader;
        private URI base;

        private ServerProcess(Process process) {
            this.process = process;
            var ready = new CompletableFuture<URI>();
            reader = Thread.ofVirtual().start(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        output.append(line).append('\n');
                        if (output.length() > 8_000) output.delete(0, output.length() - 8_000);
                        if (line.startsWith("SysML collaboration: ")) ready.complete(URI.create(line.substring("SysML collaboration: ".length())));
                    }
                    ready.completeExceptionally(new IOException("Child exited before readiness: " + output));
                } catch (Exception e) {
                    ready.completeExceptionally(e);
                }
            });
            readiness = ready;
        }

        private final CompletableFuture<URI> readiness;

        static ServerProcess start(Path data, String failAt) throws Exception {
            Path root = Path.of(System.getProperty("sysml.root")).toAbsolutePath();
            var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx1g", "-Dsysml.root=" + root));
            if (failAt != null) command.add("-Dsysml.failAt=" + failAt);
            command.addAll(List.of("-cp", System.getProperty("java.class.path"), LocalServer.class.getName(),
                    "--port", "0", "--data", data.toAbsolutePath().toString(),
                    "--libraries", Path.of(System.getProperty("sysml.pilot.library")).toAbsolutePath().toString(),
                    "--web", root.resolve("web/dist").toString()));
            var child = new ServerProcess(new ProcessBuilder(command).redirectErrorStream(true).start());
            try {
                child.base = child.readiness.get(60, TimeUnit.SECONDS);
                return child;
            } catch (Exception | Error e) {
                child.close();
                throw e;
            }
        }

        String get(String path) throws Exception {
            return send(path, null);
        }

        WorkspaceData.State state(String id) throws Exception {
            return WorkspaceService.JSON.fromJson(get("/api/workspaces/" + id), WorkspaceData.State.class);
        }

        WorkspaceData.Response action(WorkspaceData.State state, String action, Object... values) throws Exception {
            return post(route(state, action), body(state, values));
        }

        WorkspaceData.Response post(String path, JsonObject request) throws Exception {
            return WorkspaceService.JSON.fromJson(send(path, request), WorkspaceData.Response.class);
        }

        private String send(String path, JsonObject json) throws Exception {
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(java.time.Duration.ofSeconds(30));
            if (json != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.toString()));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return response.body();
        }

        @Override public void close() throws Exception {
            // Cleanup must also work when JUnit interrupts a timed-out test.
            process.destroyForcibly();
            try {
                process.onExit().orTimeout(10, TimeUnit.SECONDS).join();
            } finally {
                client.shutdownNow();
                process.getInputStream().close();
            }
            reader.join(5_000);
            assertFalse(reader.isAlive(), "Child output reader did not terminate");
        }
    }
}
