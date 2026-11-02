package app;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceStoreTest {
 @TempDir Path directory;
 @Test void exactInvalidBuffersSurviveRestartAndRejectStaleWrites() throws Exception {
   Path file=directory.resolve("state.db");
   try(var store=new WorkspaceStore(file)){
     store.create(new WorkspaceStore.Row("alice",0,"invalid\r\n{"));
     store.save(new WorkspaceStore.Row("alice",1,"new\r\n{"),0,List.of(new WorkspaceStore.Row("b1",0,"old revision")));
     assertThrows(IllegalStateException.class,()->store.save(new WorkspaceStore.Row("alice",2,"lost"),0,List.of()));
   }
   try(var store=new WorkspaceStore(file)){
     assertEquals("new\r\n{",store.get("alice").json());
     assertEquals("old revision",store.revision("b1"));
   }
 }
 @Test void publicationFinalizationIsAtomicAndIdempotencyIsScoped() throws Exception {
   try(var store=new WorkspaceStore(directory.resolve("state.db"))){
     store.create(new WorkspaceStore.Row("alice",0,"old"));
     var intent=new WorkspaceStore.Intent("op","alice","deliver",0,"refs/heads/main","a","b","new","response");
     store.prepare(intent);
     assertThrows(IllegalStateException.class,()->store.finish(new WorkspaceStore.Intent("bad","alice","deliver",99,"ref","a","b","new","bad"),
       new WorkspaceStore.Row("alice",1,"broken"),List.of()));
     assertEquals("old",store.get("alice").json());assertEquals(1,store.pending().size());
     store.finish(intent,new WorkspaceStore.Row("alice",1,"new"),List.of(new WorkspaceStore.Row("b2",0,"revision")));
     assertTrue(store.pending().isEmpty());assertEquals("response",store.operation("op","alice","deliver"));
     assertThrows(IllegalArgumentException.class,()->store.operation("op","bob","deliver"));
   }
 }
}
