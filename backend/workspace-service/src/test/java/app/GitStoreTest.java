package app;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class GitStoreTest {
 @TempDir Path dir;
 @Test void commitsAreRealAndAcceptedUpdatesUseCompareAndSwap() throws Exception{
   var git=new GitStore(dir.resolve("project.git"));
   String r0=git.commit(null,Map.of("model.sysml","10\r\n","identities.json","{}"),"System","Initial");
   assertTrue(git.advance(GitStore.MAIN,r0,null));assertEquals("10\r\n",git.read(r0,"model.sysml"));
   String r1=git.commit(r0,Map.of("model.sysml","12\r\n","identities.json","{}"),"Alice","Delivery");
   String other=git.commit(r0,Map.of("model.sysml","15\r\n","identities.json","{}"),"Bob","Concurrent");
   assertTrue(git.advance(GitStore.MAIN,r1,r0));assertFalse(git.advance(GitStore.MAIN,other,r0));
   assertEquals(r1,git.head(GitStore.MAIN));assertTrue(git.ancestor(r0,r1));assertEquals("Alice",git.author(r1));
   assertEquals(2,git.acceptedHistory().size());assertEquals(r0,git.acceptedHistory().getFirst().parentRevisionId());assertNull(git.acceptedHistory().getLast().parentRevisionId());
   assertThrows(IllegalArgumentException.class,()->git.read(r0,"../../secret"));
 }
 @Test void disjointSourceChangesMergeButOverlapNeverSilentlyWins() throws Exception{
   var git=new GitStore(dir.resolve("project.git"));
   String base="first\n\n\nlast\n";
   assertEquals("FIRST\n\n\nLAST\n",git.mergeText(base,"FIRST\n\n\nlast\n","first\n\n\nLAST\n"));
   assertThrows(IllegalArgumentException.class,()->git.mergeText("a\n","b\n","c\n"));
 }
}
