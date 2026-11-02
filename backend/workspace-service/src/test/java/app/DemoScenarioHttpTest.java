package app;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Set;

class DemoScenarioHttpTest {
    @TempDir Path data;
    @Test void frozenEndpointsShareProgressBetweenClientsAndKeepLocalBoundary() throws Exception {
        var service=new WorkspaceService(data.resolve("demo"),Path.of(System.getProperty("sysml.pilot.library")),Path.of(System.getProperty("sysml.root"),"fixtures/connected-pump.sysml"));
        try(var server=new LocalServer(service,Files.createDirectories(data.resolve("web")),0);
            var alice=HttpClient.newHttpClient();var bob=HttpClient.newHttpClient()){
            server.start();String base="http://127.0.0.1:"+server.port();
            var initial=get(alice,base);assertEquals(0,initial.completedStep());
            assertEquals(409,post(bob,base,"/api/demo/scenario/step",step(initial.generation(),2,"Alice")).statusCode());
            String reset="{\"expectedGeneration\":\""+initial.generation()+"\",\"operationId\":\"scenario-http-reset\"}";
            assertEquals(200,post(alice,base,"/api/demo/reset",reset).statusCode());
            var ready=get(bob,base);assertEquals(1,ready.completedStep());
            assertEquals(400,post(bob,base,"/api/demo/scenario/step",step(ready.generation(),2,"Other")).statusCode());
            var forbidden=HttpRequest.newBuilder(URI.create(base+"/api/demo/scenario/step")).header("Content-Type","application/json").header("Origin","https://foreign.invalid").POST(HttpRequest.BodyPublishers.ofString(step(ready.generation(),2,"Alice"))).build();
            assertEquals(403,bob.send(forbidden,HttpResponse.BodyHandlers.ofString()).statusCode());
            var published=post(alice,base,"/api/demo/scenario/step",step(ready.generation(),2,"Bob"));assertEquals(200,published.statusCode());
            var json=WorkspaceService.JSON.fromJson(published.body(),JsonObject.class);
            assertEquals(Set.of("generation","completedStep","actor","message","conflicted","resetRequired"),json.keySet());
            var progress=WorkspaceService.JSON.fromJson(published.body(),DemoScenario.State.class);assertEquals(2,progress.completedStep());assertEquals("Alice",progress.actor());assertFalse(progress.resetRequired());
            assertEquals(progress,get(bob,base));
            assertEquals(progress,WorkspaceService.JSON.fromJson(post(bob,base,"/api/demo/scenario/step",step(ready.generation(),2,"Alice")).body(),DemoScenario.State.class));
            assertEquals(409,post(bob,base,"/api/demo/scenario/step",step(initial.generation(),3,"Bob")).statusCode());
            assertEquals(409,post(bob,base,"/api/demo/scenario/step",step(ready.generation(),4,"Alice")).statusCode());
        }
    }
    private String step(String generation,int step,String actor){return "{\"expectedGeneration\":\""+generation+"\",\"step\":"+step+",\"actor\":\""+actor+"\"}";}
    private DemoScenario.State get(HttpClient client,String base) throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/demo/scenario")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());return WorkspaceService.JSON.fromJson(response.body(),DemoScenario.State.class);
    }
    private HttpResponse<String> post(HttpClient client,String base,String path,String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
