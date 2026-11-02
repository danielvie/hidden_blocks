package app;

import static app.ModelContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;

class ConnectedCollaborationTest {
    @TempDir Path data;
    private WorkspaceService service() throws Exception {
        return new WorkspaceService(data, Path.of(System.getProperty("sysml.pilot.library")),
            Path.of(System.getProperty("sysml.root"), "fixtures/connected-pump.sysml"));
    }
    private JsonObject body(WorkspaceData.State state,Object... fields) {
        var result=new JsonObject();result.addProperty("expectedRevision",state.revision());
        result.addProperty("documentVersion",state.source().version());
        for(int i=0;i<fields.length;i+=2) result.addProperty(fields[i].toString(),fields[i+1].toString());
        return result;
    }
    private WorkspaceData.State action(WorkspaceService service,WorkspaceData.State state,String action,Object... fields) throws Exception {
        return service.execute(state.workspaceId(),action,body(state,fields)).state();
    }
    private ModelElement part(WorkspaceData.State state,String name) {
        return state.analysis().elements().stream().filter(e->e.kind().equals("PartUsage")&&e.name().equals(name)).findFirst().orElseThrow();
    }
    private Attribute attribute(ModelElement element,String name) {
        return element.attributes().stream().filter(a->a.name().equals(name)).findFirst().orElseThrow();
    }
    @Test void localOverrideConflictRetainsConnectionInheritanceAndIndependentDescendant() throws Exception {
        try(var service=service()) {
            var alice=service.create("Alice").state();var bob=service.create("Bob").state();
            var originalConnection=alice.analysis().connections().getFirst();
            assertEquals(1,alice.analysis().connections().size());
            var inheritedSpeed=attribute(part(alice,"pump"),"speed");
            assertTrue(inheritedSpeed.inherited());assertFalse(inheritedSpeed.editable());
            assertNotNull(inheritedSpeed.definitionAttributeId());
            alice=action(service,alice,"edit","attributeId",attribute(part(alice,"pump"),"flowRate").id(),"expression","12");
            alice=action(service,alice,"record","description","Alice local flow");
            alice=action(service,alice,"deliver","operationId",UUID.randomUUID());
            bob=action(service,bob,"edit","attributeId",attribute(part(bob,"pump"),"flowRate").id(),"expression","15");
            bob=action(service,bob,"record","description","Bob local flow");
            bob=action(service,bob,"deliver","operationId",UUID.randomUUID());
            assertEquals(1,bob.conflicts().size());
            assertEquals(part(bob,"pump").id(),bob.conflicts().getFirst().target().elementId());
            assertTrue(bob.source().text().contains("flowRate : Real default = 10"));
            var conflict=bob.conflicts().getFirst();
            bob=action(service,bob,"add","elementId",part(bob,"pump").id(),"name","alertEnabled","typeName","Boolean","expression","true");
            bob=action(service,bob,"record","description","Independent alert");
            bob=action(service,bob,"resolve","originChangeId",conflict.originChangeId(),"conflictId",conflict.id(),"expression","14");
            assertEquals("14",attribute(part(bob,"pump"),"flowRate").value().scalar().canonical());
            assertEquals("true",attribute(part(bob,"pump"),"alertEnabled").value().scalar().canonical());
            assertEquals("1800",attribute(part(bob,"pump"),"speed").value().scalar().canonical());
            assertEquals(originalConnection,bob.analysis().connections().getFirst());
            assertFalse(part(bob,"pump").superTypeIds().isEmpty());
            assertTrue(bob.source().text().contains("VariableSpeedPump :> Pump"));
            assertTrue(bob.source().text().contains("connect pump.outlet to tank.inlet"));
            bob=action(service,bob,"deliver","operationId",UUID.randomUUID());
            alice=action(service,alice,"update");
            assertEquals("14",attribute(part(alice,"pump"),"flowRate").value().scalar().canonical());
            assertEquals(originalConnection,alice.analysis().connections().getFirst());
        }
    }
    @Test void authoredDefinitionEditIsRecordedOnceWhileInheritedRowsFollow() throws Exception {
        String id;
        try(var service=service()) {
            var alice=service.create("Alice").state();id=alice.workspaceId();
            alice=action(service,alice,"save","text",alice.source().text().replace("speed : Real = 1800","speed : Real = 1900"));
            alice=action(service,alice,"record","description","Definition speed");
            assertEquals("1900",attribute(part(alice,"pump"),"speed").value().scalar().canonical());
            assertEquals(1,alice.history().size());
        }
        try(var store=new WorkspaceStore(data.resolve("workspaces.sqlite"))) {
            var stored=WorkspaceService.JSON.fromJson(store.get(id).json(),WorkspaceData.class);
            assertEquals(1,stored.history().getFirst().change().edits().size());
            assertEquals("speed",stored.history().getFirst().change().edits().getFirst().attributeName());
        }
    }
    @Test void metadataUpgradeDoesNotRewriteOldWorkspaceOrGitHistory() throws Exception {
        String id,head,text;long revision;
        try(var service=service()){
            var state=service.create("Existing work").state();id=state.workspaceId();head=state.acceptedHead();
            text=state.source().text();revision=state.revision();
        }
        try(var store=new WorkspaceStore(data.resolve("workspaces.sqlite"))){
            var row=store.get(id);var json=WorkspaceService.JSON.fromJson(row.json(),JsonObject.class);
            json.remove("analysisVersion");
            store.save(new WorkspaceStore.Row(id,revision,json.toString()),revision,List.of());
        }
        try(var service=service()){
            var state=service.state(id);
            assertEquals(revision,state.revision());assertEquals(text,state.source().text());assertEquals(head,state.acceptedHead());
            assertEquals(1,state.analysis().connections().size());
        }
    }
}
