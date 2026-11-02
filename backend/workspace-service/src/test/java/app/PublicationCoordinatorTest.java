package app;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;import java.util.List;import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class PublicationCoordinatorTest{
 @TempDir Path root;
 @Test void failureAfterGitAdvanceFinishesExactlyOnceAfterRestart() throws Exception{
  var git=new GitStore(root.resolve("project.git"));
  String r0=git.commit(null,Map.of("model.sysml","10"),"System","Initial");
  git.advance(GitStore.MAIN,r0,null);
  String r1=git.commit(r0,Map.of("model.sysml","12"),"Alice","Delivery");
  var intent=new WorkspaceStore.Intent("operation1","alice","deliver",0,GitStore.MAIN,r0,r1,"new","result");
  try(var db=new WorkspaceStore(root.resolve("state.db"))){
   db.create(new WorkspaceStore.Row("alice",0,"old"));
   var publishing=new PublicationCoordinator(db,git,stage->{if(stage.equals("git"))throw new IllegalStateException("simulated crash");});
   assertThrows(IllegalStateException.class,()->publishing.publish(intent,List.of()));
   assertEquals(r1,git.head(GitStore.MAIN));assertEquals("old",db.get("alice").json());
  }
  try(var db=new WorkspaceStore(root.resolve("state.db"))){
   new PublicationCoordinator(db,git).recover();new PublicationCoordinator(db,git).recover();
   assertEquals("new",db.get("alice").json());assertEquals("result",db.operation("operation1","alice","deliver"));
   assertTrue(db.pending().isEmpty());assertEquals(r1,git.head(GitStore.MAIN));
  }
 }
 @Test void intentBeforeRefUpdateCanBeRecovered() throws Exception{
  var git=new GitStore(root.resolve("project.git"));
  String r0=git.commit(null,Map.of("model.sysml","10"),"System","Initial");git.advance(GitStore.MAIN,r0,null);
  String r1=git.commit(r0,Map.of("model.sysml","12"),"Alice","Delivery");
  try(var db=new WorkspaceStore(root.resolve("state.db"))){
   db.create(new WorkspaceStore.Row("alice",0,"old"));
   var i=new WorkspaceStore.Intent("operation2","alice","deliver",0,GitStore.MAIN,r0,r1,"new","ok");
   var publishing=new PublicationCoordinator(db,git,stage->{if(stage.equals("intent"))throw new IllegalStateException("simulated crash");});
   assertThrows(IllegalStateException.class,()->publishing.publish(i,List.of()));
   assertEquals(r0,git.head(GitStore.MAIN));
   new PublicationCoordinator(db,git).recover();assertEquals(r1,git.head(GitStore.MAIN));assertEquals("new",db.get("alice").json());
  }
 }
}
