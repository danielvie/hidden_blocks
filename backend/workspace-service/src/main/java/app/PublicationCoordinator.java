package app;
import java.util.List;

/** Recovery bridges SQLite and Git. Neither store is claimed to transact with the other. */
public final class PublicationCoordinator {
    public interface Boundary {void reached(String stage);}
    private final WorkspaceStore store;private final GitStore git;private final Boundary boundary;
    private final java.util.function.Function<String,List<WorkspaceStore.Row>> revisions;
    public PublicationCoordinator(WorkspaceStore store,GitStore git){this(store,git,stage->{});}
    public PublicationCoordinator(WorkspaceStore store,GitStore git,Boundary boundary){this(store,git,boundary,json->List.of());}
    public PublicationCoordinator(WorkspaceStore store,GitStore git,Boundary boundary,java.util.function.Function<String,List<WorkspaceStore.Row>> revisions){this.store=store;this.git=git;this.boundary=boundary;this.revisions=revisions;}
    public synchronized void publish(WorkspaceStore.Intent intent,List<WorkspaceStore.Row> revisions) throws Exception {
        git.retain(intent.id(),intent.candidate());boundary.reached("candidate");
        store.prepare(intent);boundary.reached("intent");
        if(!git.advance(intent.ref(),intent.candidate(),intent.oldHead())) {
            store.abort(intent.id());throw new IllegalStateException("Accepted head changed; retry delivery after refreshing.");
        }
        boundary.reached("git");
        store.finish(intent,new WorkspaceStore.Row(intent.workspaceId(),intent.expectedRevision()+1,intent.workspaceJson()),revisions);
        boundary.reached("finalized");
    }
    public synchronized void recover() throws Exception {
        for(var i:store.pending()){
            String head=git.head(i.ref());
            if(java.util.Objects.equals(head,i.oldHead())){
                if(!git.advance(i.ref(),i.candidate(),i.oldHead()))throw new IllegalStateException("Recovery head changed");
                head=i.candidate();
            }
            if(head!=null&&(head.equals(i.candidate())||git.ancestor(i.candidate(),head))){
                store.finish(i,new WorkspaceStore.Row(i.workspaceId(),i.expectedRevision()+1,i.workspaceJson()),revisions.apply(i.workspaceJson()));
            }else throw new IllegalStateException("Publication recovery requires inspection; draft work is preserved: "+i.id());
        }
    }
}
