package app;

import static app.ModelContracts.*;
import java.util.*;

/** A disposable demo generation. SQLite journals reset across the Git/database boundary. */
public final class DemoLifecycle {
    public record Info(String generation,String aliceWorkspaceId,String bobWorkspaceId,String lastResetOperation) {}
    public record ResetIntent(String expectedGeneration,Info info,String rootCommit,
                              WorkspaceData alice,WorkspaceData bob) {}
    private final WorkspaceStore store;
    private final GitStore git;
    private final ParserAdapter parser;
    private final String reference;
    public DemoLifecycle(WorkspaceStore store,GitStore git,ParserAdapter parser,String reference) {
        this.store=store;this.git=git;this.parser=parser;this.reference=reference;
    }
    public Info info() throws Exception {
        return WorkspaceService.JSON.fromJson(store.metadata("demo"),Info.class);
    }
    public synchronized Info ensure() throws Exception {
        recover();
        var info=info();if(info!=null)return info;
        String head=git.head(GitStore.MAIN);
        Map<String,String> identities=WorkspaceService.JSON.fromJson(git.read(head,"identities.json"),
            new com.google.gson.reflect.TypeToken<Map<String,String>>(){}.getType());
        var alice=workspace("Alice",head,git.read(head,"model.sysml"),identities);
        var bob=workspace("Bob",head,git.read(head,"model.sysml"),identities);
        info=new Info(UUID.randomUUID().toString(),alice.workspaceId(),bob.workspaceId(),null);
        store.installDemo(WorkspaceService.JSON.toJson(info),row(alice),row(bob),false);
        return info;
    }
    public synchronized Info reset(String expected,String operation) throws Exception {
        recover();
        var existing=ensure();
        if(operation.equals(existing.lastResetOperation()))return existing;
        if(!Objects.equals(expected,existing.generation()))
            throw new Problem(409,"stale","The demo was already reset. Reload the current Alice/Bob environments.");
        String generation=UUID.randomUUID().toString();
        var source=new SourceDocument("model.sysml",0,reference);
        var result=parser.analyze("reset-"+generation,0,List.of(source),Map.of());
        ReplayMaterializer.requireValid(result.analysis());
        String root=git.commit(null,Map.of("model.sysml",reference,"identities.json",WorkspaceService.JSON.toJson(result.identities())),
            "System","Initial reference model");
        var alice=workspace("Alice",root,reference,result.identities());
        var bob=workspace("Bob",root,reference,result.identities());
        var info=new Info(generation,alice.workspaceId(),bob.workspaceId(),operation);
        var intent=new ResetIntent(expected,info,root,alice,bob);
        store.metadata("reset",WorkspaceService.JSON.toJson(intent));boundary("reset-intent");
        finish(intent);return info;
    }
    public synchronized void recover() throws Exception {
        var intent=WorkspaceService.JSON.fromJson(store.metadata("reset"),ResetIntent.class);
        if(intent!=null)finish(intent);
    }
    private void finish(ResetIntent intent) throws Exception {
        git.resetDemoHistory(intent.rootCommit());boundary("reset-git");
        store.installDemo(WorkspaceService.JSON.toJson(intent.info()),row(intent.alice()),row(intent.bob()),true);
        boundary("reset-finalized");
    }
    private WorkspaceData workspace(String actor,String head,String text,Map<String,String> identities) {
        String id=UUID.randomUUID().toString();
        var source=new SourceDocument("model.sysml",0,text);
        var result=parser.analyze(id,0,List.of(source),identities);
        ReplayMaterializer.requireValid(result.analysis());
        return new WorkspaceData(id,actor,0,head,source,result.identities(),result.analysis(),result.analysis(),
            List.of(),List.of(),false,0);
    }
    private static WorkspaceStore.Row row(WorkspaceData state) {
        return new WorkspaceStore.Row(state.workspaceId(),state.revision(),WorkspaceService.JSON.toJson(state));
    }
    private static void boundary(String stage) {
        if(stage.equals(System.getProperty("sysml.failAt")))Runtime.getRuntime().halt(86);
    }
}
