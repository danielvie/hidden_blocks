package app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class LocalBoundaryTest {
    @TempDir Path directory;
    private LocalServer server;
    private String host;
    private String origin;

    @BeforeEach void start() throws Exception {
        Path web = Files.createDirectories(directory.resolve("web"));
        Files.createDirectories(web.resolve("nested"));
        Files.writeString(web.resolve("index.html"), "<html>test-owned web root</html>");
        Files.writeString(directory.resolve("outside-secret.txt"), "outside-web-sentinel-9461");
        var service = new WorkspaceService(directory.resolve("data"), Path.of(System.getProperty("sysml.pilot.library")),
                Path.of(System.getProperty("sysml.root"), "fixtures/pump.sysml"));
        try {
            server = new LocalServer(service, web, 0);
            server.start();
            host = "127.0.0.1:" + server.port();
            origin = "http://" + host;
        } catch (Exception | Error e) {
            service.close();
            throw e;
        }
    }

    @AfterEach void stop() throws Exception {
        if (server != null) server.close();
    }

    @Test void foreignRequestsAndInvalidJsonCannotMutateButSameOriginWorks() throws Exception {
        String create = "{\"actor\":\"Untrusted\"}";
        assertEquals(403, request("POST", "/api/workspaces", Map.of("Origin", "https://foreign.invalid",
                "Content-Type", "application/json"), create).status());
        assertEquals(403, request("GET", "/api/health", Map.of("Host", "foreign.invalid:" + server.port()), "").status());
        assertEquals(403, request("POST", "/api/workspaces", Map.of("Sec-Fetch-Site", "cross-site",
                "Content-Type", "application/json"), create).status());
        assertEquals(415, request("POST", "/api/workspaces", Map.of("Content-Type", "text/plain"), create).status());
        assertEquals(415, request("POST", "/api/workspaces", Map.of(), create).status());
        assertEquals(400, request("POST", "/api/workspaces", Map.of("Content-Type", "application/json"), "{").status());
        assertEquals(400, request("POST", "/api/workspaces", Map.of("Content-Type", "application/json"), "[]").status());
        var project = request("GET", "/api/project", Map.of("Origin", origin), "");
        assertEquals(200, project.status());
        assertTrue(WorkspaceService.JSON.fromJson(project.body(), com.google.gson.JsonObject.class)
                .getAsJsonArray("workspaces").isEmpty(), "Rejected requests must not create workspaces");
        var accepted = request("POST", "/api/workspaces", Map.of("Origin", origin,
                "Content-Type", "application/json; charset=UTF-8"), "{\"actor\":\"Trusted\"}");
        assertEquals(200, accepted.status(), accepted.body());
        assertEquals("Trusted", WorkspaceService.JSON.fromJson(accepted.body(), WorkspaceData.Response.class).state().actor());
        assertTrue(accepted.headers().toLowerCase().contains("x-content-type-options: nosniff"));
        assertTrue(accepted.headers().toLowerCase().contains("cache-control: no-store"));
        assertEquals(200, request("GET", "/api/health", Map.of("Host", "localhost:" + server.port(),
                "Origin", "http://localhost:" + server.port()), "").status());
    }

    @Test void jsonLikeMediaTypeIsNotApplicationJson() throws Exception {
        var response = request("POST", "/api/workspaces", Map.of("Origin", origin,
                "Content-Type", "application/jsonp"), "{\"actor\":\"Wrong media type\"}");
        assertEquals(415, response.status(), "Only application/json, optionally with parameters, is a JSON request media type");
    }

    @Test void destructiveResetStillRequiresTheNormalLocalBoundaryAndCurrentGeneration() throws Exception {
        var demo=WorkspaceService.JSON.fromJson(request("GET","/api/demo",Map.of("Origin",origin),"").body(),WorkspaceService.DemoState.class);
        String body="{\"expectedGeneration\":\""+demo.generation()+"\",\"operationId\":\"boundary-reset\"}";
        assertEquals(403,request("POST","/api/demo/reset",Map.of("Origin","https://foreign.invalid","Content-Type","application/json"),body).status());
        assertEquals(415,request("POST","/api/demo/reset",Map.of("Origin",origin,"Content-Type","text/plain"),body).status());
        assertEquals(409,request("POST","/api/demo/reset",Map.of("Origin",origin,"Content-Type","application/json"),body.replace(demo.generation(),"stale")).status());
        assertEquals(demo.generation(),WorkspaceService.JSON.fromJson(request("GET","/api/demo",Map.of(),"").body(),WorkspaceService.DemoState.class).generation());
    }

    @Test void staticTraversalCannotReadFilesOutsideTheWebRoot() throws Exception {
        var index = request("GET", "/", Map.of("Origin", origin), "");
        assertEquals(200, index.status());
        assertEquals("<html>test-owned web root</html>", index.body());
        for (String route : new String[]{"/../outside-secret.txt", "/%2e%2e/outside-secret.txt",
                "/..%2foutside-secret.txt", "/nested/%2e%2e/%2e%2e/outside-secret.txt",
                "/%2e%2e%5coutside-secret.txt", "/%00outside-secret.txt"}) {
            var response = request("GET", route, Map.of(), "");
            assertTrue(response.status() >= 400 && response.status() < 500, route + " returned " + response.status());
            assertFalse(response.body().contains("outside-web-sentinel-9461"), route);
        }
        assertEquals(200, request("GET", "/api/health", Map.of(), "").status());
    }

    private record Reply(int status, String headers, String body) {}

    // A raw socket preserves hostile Host headers and dot segments that HTTP clients may normalize.
    private Reply request(String method, String path, Map<String, String> supplied, String body) throws Exception {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Host", host);
        headers.put("Connection", "close");
        headers.put("Content-Length", Integer.toString(body.getBytes(StandardCharsets.UTF_8).length));
        headers.putAll(supplied);
        var wire = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        headers.forEach((key, value) -> wire.append(key).append(": ").append(value).append("\r\n"));
        wire.append("\r\n").append(body);
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5_000);
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(wire.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int split = response.indexOf("\r\n\r\n");
            assertTrue(split >= 0, "Expected a complete HTTP response");
            return new Reply(Integer.parseInt(response.substring(0, response.indexOf("\r\n")).split(" ")[1]),
                    response.substring(0, split), response.substring(split + 4));
        }
    }
}
