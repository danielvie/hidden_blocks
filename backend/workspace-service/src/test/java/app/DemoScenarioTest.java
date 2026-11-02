package app;

import static app.ModelContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;

class DemoScenarioTest {
    @TempDir Path data;
    private WorkspaceService service() throws Exception {return service("connected-pump.sysml");}
    private WorkspaceService service(String fixture) throws Exception {
        return new WorkspaceService(data,Path.of(System.getProperty("sysml.pilot.library")),Path.of(System.getProperty("sysml.root"),"fixtures",fixture));
    }
    private DemoScenario.State reset(WorkspaceService service) throws Exception {
        service.resetDemo(service.demo().generation(),UUID.randomUUID().toString());
        try(var store=new WorkspaceStore(data.resolve("workspaces.sqlite"))){assertNull(store.metadata("scenario"));}
        return service.scenario();
    }
    private WorkspaceData.State act(WorkspaceService service,WorkspaceData.State state,String action,String... fields) throws Exception {
        var request=new JsonObject();request.addProperty("expectedRevision",state.revision());request.addProperty("documentVersion",state.source().version());
        for(int i=0;i<fields.length;i+=2)request.addProperty(fields[i],fields[i+1]);
        return service.execute(state.workspaceId(),action,request).state();
    }
    private ModelElement pump(WorkspaceData.State state){
        return state.analysis().elements().stream().filter(e->e.name().equals("pump")&&e.kind().equals("PartUsage")).findFirst()
            .orElseGet(()->state.analysis().elements().stream().filter(e->e.name().equals("Pump")&&e.kind().equals("PartDefinition")).findFirst().orElseThrow());
    }
    private Attribute attribute(WorkspaceData.State state,String name){return pump(state).attributes().stream().filter(a->a.name().equals(name)).findFirst().orElseThrow();}
    private String value(WorkspaceData.State state,String name){return attribute(state,name).value().scalar().canonical();}
    private void rejected(int http,String status,org.junit.jupiter.api.function.Executable operation){var error=assertThrows(Problem.class,operation);assertEquals(http,error.http);assertEquals(status,error.status);}
    private void through(WorkspaceService service,String generation,int last) throws Exception {
        for(int step=2;step<=last;step++){
            var result=service.scenarioStep(generation,step,"Alice");
            assertEquals(step,result.completedStep(),result.message());assertFalse(result.resetRequired(),result.message());
        }
    }
    @ParameterizedTest @ValueSource(strings={"Alice","Bob"})
    void actualSevenStepsPreserveSpecializationWiresAndDescendantsWithEitherResolver(String resolver) throws Exception {
        String generation,bobId;List<Connection> wires;List<String> supers;String b1,b2;
        try(var service=service()){
            assertEquals(0,service.scenario().completedStep());
            var progress=reset(service);generation=progress.generation();assertEquals(1,progress.completedStep());
            var initial=service.demo();wires=initial.alice().analysis().connections();supers=pump(initial.alice()).superTypeIds();
            assertFalse(wires.isEmpty());assertFalse(supers.isEmpty());
            String head=initial.alice().acceptedHead();
            var stage2=service.scenarioStep(generation,2,"Bob");assertEquals("Alice",stage2.actor());
            var alice=service.demo().alice();assertEquals("12",value(alice,"flowRate"));assertTrue(alice.dirty());assertTrue(alice.history().isEmpty());assertEquals(head,alice.acceptedHead());
            var stage3=service.scenarioStep(generation,3,"Alice");assertEquals("Bob",stage3.actor());
            var bob=service.demo().bob();assertEquals("15",value(bob,"flowRate"));assertTrue(bob.dirty());assertTrue(bob.history().isEmpty());assertEquals(head,bob.acceptedBaseRevision());
            assertEquals(4,service.scenarioStep(generation,4,"Bob").completedStep());
            alice=service.demo().alice();assertFalse(alice.dirty());assertEquals(1,alice.history().size());assertTrue(alice.history().getFirst().accepted());
            String accepted12=alice.acceptedHead();assertNotEquals(head,accepted12);
            var stage5=service.scenarioStep(generation,5,"Alice");assertEquals(5,stage5.completedStep());assertTrue(stage5.conflicted());assertFalse(stage5.resetRequired());
            bob=service.demo().bob();bobId=bob.workspaceId();assertEquals(accepted12,bob.acceptedHead());
            var conflict=bob.conflicts().getFirst();assertEquals("10",conflict.base().value().scalar().canonical());assertEquals("12",conflict.incoming().value().scalar().canonical());assertEquals("15",conflict.local().value().scalar().canonical());
            b1=bob.history().getFirst().changeId();
            var stage6=service.scenarioStep(generation,6,"Alice");assertEquals(6,stage6.completedStep());assertTrue(stage6.conflicted());
            bob=service.demo().bob();assertFalse(bob.dirty());assertEquals(2,bob.history().size());assertEquals(conflict,bob.conflicts().getFirst());assertEquals("true",value(bob,"alertEnabled"));
            b2=bob.history().getLast().changeId();assertNotEquals(b1,b2);
            var snapshot=service.demo();
            assertEquals(stage6,service.scenarioStep(generation,4,"Bob"));assertEquals(snapshot,service.demo(),"Completed retry must not record or deliver again");
            assertEquals(stage6,service.scenario(),"Read-only inspection must not invalidate progress");
        }
        try(var service=service()){
            assertEquals(6,service.scenario().completedStep());
            var complete=service.scenarioStep(generation,7,resolver);assertEquals(7,complete.completedStep(),complete.message());assertEquals(resolver,complete.actor());assertFalse(complete.resetRequired());assertFalse(complete.conflicted());assertTrue(complete.message().contains("preserved"));
            var demo=service.demo();
            for(var state:List.of(demo.alice(),demo.bob())){
                assertEquals("14",value(state,"flowRate"));assertEquals("true",value(state,"alertEnabled"));assertEquals("1800",value(state,"speed"));
                assertEquals(wires,state.analysis().connections());assertEquals(supers,pump(state).superTypeIds());
                assertTrue(state.source().text().contains("VariableSpeedPump :> Pump"));assertTrue(state.source().text().contains("connect pump.outlet to tank.inlet"));assertTrue(state.source().text().contains("flowRate : Real default = 10"));
                assertFalse(state.dirty());assertTrue(state.conflicts().isEmpty());assertEquals(state.acceptedHead(),state.acceptedBaseRevision());
            }
            assertEquals(List.of(b1,b2),demo.bob().history().stream().map(WorkspaceData.HistoryEntry::changeId).toList());
            assertTrue(demo.bob().history().stream().allMatch(WorkspaceData.HistoryEntry::accepted));assertEquals("Bob",demo.bob().actor());
            assertEquals(complete,service.scenarioStep(generation,7,resolver.equals("Alice")?"Bob":"Alice"));assertEquals(demo,service.demo());
            try(var store=new WorkspaceStore(data.resolve("workspaces.sqlite"))){
                var stored=WorkspaceService.JSON.fromJson(store.get(bobId).json(),WorkspaceData.class);
                assertNotNull(store.operation("scenario-"+generation+"-step-4",demo.alice().workspaceId(),"deliver"));
                assertNotNull(store.operation("scenario-"+generation+"-step-7",bobId,"deliver"));
                assertEquals("Bob",stored.actor());assertEquals("Bob",stored.history().getFirst().change().actor());
                assertEquals(resolver,stored.history().getFirst().change().resolutions().getFirst().actor());
                assertEquals("14",stored.history().getFirst().change().resolutions().getFirst().value().scalar().canonical());
                assertEquals(1,stored.history().getLast().change().edits().size());assertEquals("alertEnabled",stored.history().getLast().change().edits().getFirst().attributeName());
            }
            var fresh=reset(service);assertEquals(1,fresh.completedStep());assertFalse(fresh.resetRequired());assertFalse(fresh.conflicted());assertNotEquals(generation,fresh.generation());
            rejected(409,"stale",()->service.scenarioStep(generation,2,"Alice"));
        }
        // Reset discards old workspace data and its scenario metadata.
        try(var store=new WorkspaceStore(data.resolve("workspaces.sqlite"))){assertNull(store.get(bobId));}
    }
    @Test void authoredLegacyDefinitionStillWorks() throws Exception {
        try(var service=service("pump.sysml")){
            var progress=reset(service);through(service,progress.generation(),7);
            assertEquals("14",value(service.demo().alice(),"flowRate"));assertEquals("true",value(service.demo().alice(),"alertEnabled"));
        }
    }
    @Test void orderValidationAndManualDraftChangesCannotOverwriteWork() throws Exception {
        try(var service=service()){
            var initial=service.scenario();rejected(409,"out-of-order",()->service.scenarioStep(initial.generation(),2,"Alice"));
            var progress=reset(service);String generation=progress.generation();
            rejected(409,"out-of-order",()->service.scenarioStep(generation,3,"Alice"));
            rejected(400,"invalid",()->service.scenarioStep(generation,2,"Mallory"));rejected(400,"invalid",()->service.scenarioStep(generation,8,"Alice"));
            var stage2=service.scenarioStep(generation,2,"Alice");
            var bob=service.demo().bob();bob=act(service,bob,"edit","attributeId",attribute(bob,"flowRate").id(),"expression","19");
            var changed=bob;var invalid=service.scenario();assertTrue(invalid.resetRequired());assertEquals(2,invalid.completedStep());assertTrue(invalid.message().contains("Reset"));
            rejected(409,"reset-required",()->service.scenarioStep(generation,3,"Bob"));assertEquals(changed,service.demo().bob());
            assertEquals(invalid,service.scenarioStep(generation,2,"Alice"));assertNotEquals(stage2,invalid);
            assertFalse(reset(service).resetRequired());
        }
    }
    @Test void changesBeforeFirstScenarioReadAndExternalAcceptedHistoryRequireReset() throws Exception {
        try(var service=service()){
            var demo=service.demo();var bob=act(service,demo.bob(),"edit","attributeId",attribute(demo.bob(),"flowRate").id(),"expression","19");
            assertTrue(service.scenario().resetRequired());assertEquals(bob,service.demo().bob());
            var progress=reset(service);var outsider=service.create("Manual editor").state();
            outsider=act(service,outsider,"edit","attributeId",attribute(outsider,"flowRate").id(),"expression","20");
            outsider=act(service,outsider,"record","description","Outside guided history");act(service,outsider,"deliver","operationId","external-manual-delivery");
            assertTrue(service.scenario().resetRequired());rejected(409,"reset-required",()->service.scenarioStep(progress.generation(),2,"Alice"));
        }
    }
    @Test void manualWorkAfterTheAlertIsPreservedInsteadOfBeingSilentlyIntegrated() throws Exception {
        try(var service=service()){
            var progress=reset(service);through(service,progress.generation(),6);
            var bob=service.demo().bob();bob=act(service,bob,"add","elementId",pump(bob).id(),"name","manualAlert","typeName","Boolean","expression","true");
            bob=act(service,bob,"record","description","Keep this later manual work");var manual=bob;
            rejected(409,"reset-required",()->service.scenarioStep(progress.generation(),7,"Alice"));
            assertEquals(manual,service.demo().bob());assertEquals(3,manual.history().size());assertEquals("true",value(manual,"manualAlert"));assertEquals("true",value(manual,"alertEnabled"));assertFalse(manual.conflicts().isEmpty());
        }
    }
    @Test void partialPublicationKeepsRecordedWorkAndDoesNotCompleteStage() throws Exception {
        try(var service=service()){
            var progress=reset(service);through(service,progress.generation(),3);
            Path lock=data.resolve("project.git/refs/heads/main.lock");Files.writeString(lock,"test-owned publication failure");
            DemoScenario.State failed;
            try{failed=service.scenarioStep(progress.generation(),4,"Alice");}finally{Files.delete(lock);}
            assertEquals(3,failed.completedStep());assertTrue(failed.resetRequired());assertTrue(failed.message().contains("did not finish"));
            var alice=service.demo().alice();assertEquals("12",value(alice,"flowRate"));assertEquals(1,alice.history().size());assertFalse(alice.history().getFirst().accepted());assertFalse(alice.dirty());
            rejected(409,"reset-required",()->service.scenarioStep(progress.generation(),4,"Alice"));assertEquals(alice,service.demo().alice());
        }
    }
}
