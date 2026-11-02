package app;
import static org.junit.jupiter.api.Assertions.*;
import static app.ModelContracts.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;import java.util.*;

class CollaborationTest {
 @TempDir Path data;
 private WorkspaceService service() throws Exception{return new WorkspaceService(data,Path.of(System.getProperty("sysml.pilot.library")),Path.of(System.getProperty("sysml.root"),"fixtures/pump.sysml"));}
 private JsonObject body(WorkspaceData.State s,Object...args){
  var j=new JsonObject();j.addProperty("expectedRevision",s.revision());j.addProperty("documentVersion",s.source().version());
  for(int i=0;i<args.length;i+=2){Object v=args[i+1];if(v instanceof Number n)j.addProperty(args[i].toString(),n);else j.addProperty(args[i].toString(),v.toString());}return j;
 }
 private WorkspaceData.State action(WorkspaceService service,WorkspaceData.State state,String action,Object...args) throws Exception{
  return service.execute(state.workspaceId(),action,body(state,args)).state();
 }
 private Attribute attribute(WorkspaceData.State state,String name){return state.analysis().elements().stream().flatMap(e->e.attributes().stream()).filter(a->a.name().equals(name)).findFirst().orElseThrow();}
 @Test void completeRealParserGitWorkflowPreservesDescendantAndSurvivesRestart() throws Exception {
  String aliceId,bobId,finalHead;
  try(var service=service()){
   var alice=service.create("Alice").state();var bob=service.create("Bob").state();aliceId=alice.workspaceId();bobId=bob.workspaceId();
   alice=action(service,alice,"save","text",alice.source().text().replace("= 10;","= 12;"));
   alice=action(service,alice,"record","description","Alice flow 12");
   alice=action(service,alice,"deliver","operationId",UUID.randomUUID());
   String r1=alice.acceptedHead();
   bob=action(service,bob,"edit","attributeId",attribute(bob,"flowRate").id(),"expression","15");
   bob=action(service,bob,"record","description","Bob flow 15");
   var delivery=service.execute(bob.workspaceId(),"deliver",body(bob,"operationId",UUID.randomUUID()));
   assertEquals("needs-resolution",delivery.status());bob=delivery.state();assertEquals(r1,bob.acceptedHead());
   assertEquals(1,bob.conflicts().size());var conflict=bob.conflicts().getFirst();
   assertEquals("12",conflict.incoming().value().scalar().canonical());assertEquals("Alice",conflict.incoming().actor());
   bob=action(service,bob,"add","elementId",attribute(bob,"flowRate").ownerId(),"name","alertEnabled","typeName","Boolean","expression","true");
   bob=action(service,bob,"record","description","Add alert while conflicted");assertEquals(2,bob.history().size());
   String shareId=UUID.randomUUID().toString();bob=action(service,bob,"share","operationId",shareId,"description","Unresolved Bob");
   assertEquals(1,service.draft(shareId).state().conflicts().size());assertEquals(alice.source().text(),service.state(aliceId).source().text());
   bob=action(service,bob,"resolve","originChangeId",conflict.originChangeId(),"conflictId",conflict.id(),"expression","14");
   assertTrue(bob.conflicts().isEmpty());assertEquals("14",attribute(bob,"flowRate").value().scalar().canonical());
   assertEquals("true",attribute(bob,"alertEnabled").value().scalar().canonical());
   String operation=UUID.randomUUID().toString();var request=body(bob,"operationId",operation);
   var result=service.execute(bobId,"deliver",request);bob=result.state();finalHead=bob.acceptedHead();
   assertEquals(finalHead,service.execute(bobId,"deliver",request).state().acceptedHead());
   alice=action(service,alice,"update");assertEquals("14",attribute(alice,"flowRate").value().scalar().canonical());
   assertEquals("true",attribute(alice,"alertEnabled").value().scalar().canonical());assertNotEquals(r1,finalHead);
  }
  try(var service=service()){
   assertEquals(finalHead,service.state(bobId).acceptedHead());
   assertEquals("14",attribute(service.state(aliceId),"flowRate").value().scalar().canonical());
  }
 }
 @Test void concurrentAdditionUsesCanonicalIdentityAndRemainsResolvable() throws Exception {
  try(var service=service()){
   var alice=service.create("Alice").state();var bob=service.create("Bob").state();
   alice=action(service,alice,"add","elementId",attribute(alice,"flowRate").ownerId(),"name","limit","typeName","Real","expression","20");
   alice=action(service,alice,"record","description","Alice limit");
   alice=action(service,alice,"deliver","operationId",UUID.randomUUID());
   String canonical=attribute(alice,"limit").id();
   bob=action(service,bob,"add","elementId",attribute(bob,"flowRate").ownerId(),"name","limit","typeName","Real","expression","30");
   bob=action(service,bob,"record","description","Bob limit");
   bob=action(service,bob,"deliver","operationId",UUID.randomUUID());
   var conflict=bob.conflicts().getFirst();assertEquals(canonical,conflict.target().attributeId());
   bob=action(service,bob,"resolve","originChangeId",conflict.originChangeId(),"conflictId",conflict.id(),"expression","25");
   assertTrue(bob.conflicts().isEmpty());assertEquals(canonical,attribute(bob,"limit").id());
   assertEquals("25",attribute(bob,"limit").value().scalar().canonical());
  }
 }
 @Test void commentsSurviveDeliveryAndUnsupportedDeclarationsCannotBeRecorded() throws Exception {
  try(var service=service()){
   var alice=service.create("Alice").state();
   var unsupported=action(service,alice,"save","text",alice.source().text().replace("part def Pump {","part def Pump { port extra;"));
   assertThrows(Problem.class,()->service.execute(unsupported.workspaceId(),"record",body(unsupported,"description","Unsupported port")));
   alice=action(service,unsupported,"save","text",alice.source().text().replace("= 10;","= 12;").replace("Keep this comment","Preserve Alice's comment"));
   alice=action(service,alice,"record","description","Flow and comment");
   alice=action(service,alice,"deliver","operationId",UUID.randomUUID());
   assertTrue(alice.source().text().contains("Preserve Alice's comment"));
  }
 }
 @Test void invalidDraftPersistsAndStaleMutationCannotOverwriteIt() throws Exception {
  String id;
  try(var service=service()){
   var original=service.create("Alice").state();id=original.workspaceId();
   var invalid=action(service,original,"save","text","package Broken {");
   assertEquals(AnalysisState.INVALID,invalid.analysis().state());assertNotNull(invalid.lastValidAnalysis());
   assertThrows(Problem.class,()->service.execute(id,"save",body(original,"text","lost")));
   assertThrows(Problem.class,()->service.execute(id,"record",body(invalid,"description","invalid")));
  }
  try(var service=service()){assertEquals("package Broken {",service.state(id).source().text());}
 }
}
