package app;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DemoLifecycleTest {
    @TempDir Path data;
    private WorkspaceService service() throws Exception {
        return new WorkspaceService(data.resolve("demo"),Path.of(System.getProperty("sysml.pilot.library")),
            Path.of(System.getProperty("sysml.root"),"fixtures/water-tank.sysml"));
    }
    private JsonObject body(WorkspaceData.State state,Object... values) {
        var request=new JsonObject();request.addProperty("expectedRevision",state.revision());
        request.addProperty("documentVersion",state.source().version());
        for(int i=0;i<values.length;i+=2)request.addProperty(values[i].toString(),values[i+1].toString());
        return request;
    }
    private WorkspaceData.State act(WorkspaceService service,WorkspaceData.State state,String action,Object... values) throws Exception {
        return service.execute(state.workspaceId(),action,body(state,values)).state();
    }
    @Test void bothActorsAreReadyBeforeEitherDeliversAndReopeningDoesNotOverwriteThem() throws Exception {
        String generation,aliceId,bobId;
        try(var service=service()){
            var demo=service.demo();generation=demo.generation();aliceId=demo.alice().workspaceId();bobId=demo.bob().workspaceId();
            assertEquals("Alice",demo.alice().actor());assertEquals("Bob",demo.bob().actor());
            assertNotEquals(aliceId,bobId);
            assertEquals(demo.alice().acceptedBaseRevision(),demo.bob().acceptedBaseRevision());
            assertEquals(3,demo.alice().analysis().connections().size());
            var alice=act(service,demo.alice(),"save","text",demo.alice().source().text().replace("flowRate : Real = 10","flowRate : Real = 12"));
            alice=act(service,alice,"record","description","Alice delivers before opening Bob");
            alice=act(service,alice,"deliver","operationId",UUID.randomUUID());
            var reopened=service.demo();
            assertEquals(generation,reopened.generation());
            assertEquals(bobId,reopened.bob().workspaceId());
            assertEquals(demo.bob().acceptedBaseRevision(),reopened.bob().acceptedBaseRevision());
            assertTrue(reopened.bob().source().text().contains("flowRate : Real = 10"));
            assertTrue(reopened.alice().source().text().contains("flowRate : Real = 12"));
        }
        try(var service=service()){
            var reopened=service.demo();assertEquals(generation,reopened.generation());
            assertEquals(aliceId,reopened.alice().workspaceId());assertEquals(bobId,reopened.bob().workspaceId());
        }
    }
    @Test void resetDiscardsBothActorsHistoryDraftsAndOldRequestIdsWithoutTouchingOtherDirectories() throws Exception {
        Path sentinel=data.resolve("outside-demo.txt");Files.writeString(sentinel,"keep");
        try(var service=service()){
            var original=service.demo();var alice=act(service,original.alice(),"save","text",original.alice().source().text().replace("flowRate : Real = 10","flowRate : Real = 12"));
            alice=act(service,alice,"record","description","Discard this");
            String shared=UUID.randomUUID().toString();
            alice=act(service,alice,"share","operationId",shared,"description","Discard draft");
            alice=act(service,alice,"deliver","operationId",UUID.randomUUID());
            var bob=act(service,original.bob(),"save","text",original.bob().source().text().replace("flowRate : Real = 10","flowRate : Real = 15"));
            bob=act(service,bob,"record","description","Discard Bob conflict");
            bob=act(service,bob,"deliver","operationId",UUID.randomUUID());
            assertEquals(1,bob.conflicts().size());
            String op=UUID.randomUUID().toString();var fresh=service.resetDemo(original.generation(),op);
            assertNotEquals(original.generation(),fresh.generation());
            assertNotEquals(original.alice().workspaceId(),fresh.alice().workspaceId());
            assertEquals(fresh.alice().source().text(),fresh.bob().source().text());
            assertTrue(fresh.alice().source().text().contains("flowRate : Real = 10"));
            assertTrue(fresh.alice().history().isEmpty());assertTrue(fresh.bob().history().isEmpty());
            assertTrue(fresh.bob().conflicts().isEmpty());assertFalse(fresh.alice().dirty());
            assertEquals(fresh.alice().acceptedBaseRevision(),fresh.bob().acceptedBaseRevision());
            var project=service.project();
            assertEquals(2,((java.util.List<?>)project.get("workspaces")).size());
            assertEquals(1,((java.util.List<?>)project.get("acceptedHistory")).size());
            assertTrue(((java.util.List<?>)project.get("drafts")).isEmpty());
            assertThrows(Problem.class,()->service.state(original.alice().workspaceId()));
            assertThrows(Problem.class,()->service.draft(shared));
            assertThrows(Problem.class,()->service.execute(original.bob().workspaceId(),"save",body(original.bob(),"text","stale")));
            assertEquals(fresh,service.resetDemo(original.generation(),op),"Same reset operation must not erase another fresh generation");
            var changed=act(service,fresh.alice(),"save","text",fresh.alice().source().text().replace("flowRate : Real = 10","flowRate : Real = 18"));
            var retried=service.resetDemo(original.generation(),op);
            assertEquals(fresh.generation(),retried.generation());assertEquals(changed,retried.alice(),"A delayed retry must not wipe edits made after the successful reset");
            assertThrows(Problem.class,()->service.resetDemo(original.generation(),UUID.randomUUID().toString()));
        }
        assertEquals("keep",Files.readString(sentinel));
        try(var service=service()){assertEquals(2,((java.util.List<?>)service.project().get("workspaces")).size());}
        try(var store=new WorkspaceStore(data.resolve("demo/workspaces.sqlite"))){assertNull(store.metadata("reset"));assertTrue(store.pending().isEmpty());}
    }
    @Test void corruptResetParametersCannotClearAWorkingDemo() throws Exception {
        try(var service=service()){
            var demo=service.demo();
            assertThrows(Problem.class,()->service.resetDemo(demo.generation(),"../../escape"));
            assertThrows(Problem.class,()->service.resetDemo("stale",UUID.randomUUID().toString()));
            assertEquals(demo.generation(),service.demo().generation());
        }
    }
}
