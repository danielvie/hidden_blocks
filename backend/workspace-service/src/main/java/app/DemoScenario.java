package app;

import static app.ModelContracts.*;
import com.google.gson.JsonObject;
import java.util.*;

/** Fixed demo shortcuts. Called only while holding the WorkspaceService monitor. */
public final class DemoScenario {
    public record State(String generation,int completedStep,String actor,String message,boolean conflicted,boolean resetRequired) {}
    private record Progress(State state,long aliceRevision,long bobRevision,String acceptedHead) {}
    private final WorkspaceService service;
    private final WorkspaceStore store;
    private final DemoLifecycle demo;
    private static final String MANUAL="Work changed outside the guided steps. Saved work is preserved. Reset to restart the demonstration.";
    DemoScenario(WorkspaceService service,WorkspaceStore store,DemoLifecycle demo) {
        this.service=service;this.store=store;this.demo=demo;
    }
    public State state() throws Exception {
        var info=demo.ensure();var alice=service.state(info.aliceWorkspaceId());var bob=service.state(info.bobWorkspaceId());
        var progress=WorkspaceService.JSON.fromJson(store.metadata("scenario"),Progress.class);
        if(progress==null){
            boolean changed=alice.revision()!=0||bob.revision()!=0||!alice.acceptedBaseRevision().equals(alice.acceptedHead())||!bob.acceptedBaseRevision().equals(bob.acceptedHead());
            var state=new State(info.generation(),info.lastResetOperation()==null?0:1,"Alice",
                changed?MANUAL:info.lastResetOperation()==null?"Reset to start the demonstration.":"Reset complete. Alice and Bob start with flowRate 10.",conflicted(alice,bob),changed);
            save(state,alice,bob);return state;
        }
        var previous=progress.state();
        boolean changed=!previous.generation().equals(info.generation())||progress.aliceRevision()!=alice.revision()||progress.bobRevision()!=bob.revision()||!progress.acceptedHead().equals(alice.acceptedHead());
        var current=new State(info.generation(),previous.completedStep(),previous.actor(),
            changed&&!previous.resetRequired()?MANUAL:previous.message(),conflicted(alice,bob),previous.resetRequired()||changed);
        if(!current.equals(previous))save(current,alice,bob);
        return current;
    }
    public State step(String expectedGeneration,long step,String actor) throws Exception {
        if(!"Alice".equals(actor)&&!"Bob".equals(actor))throw new Problem(400,"invalid","Choose Alice or Bob as the current actor");
        if(step<2||step>7)throw new Problem(400,"invalid","Choose a demonstration step from 2 through 7");
        var before=state();
        if(!before.generation().equals(expectedGeneration))throw new Problem(409,"stale","The demo was reset. Refresh before running another step.");
        if(step<=before.completedStep())return before;
        if(before.resetRequired())throw new Problem(409,"reset-required",before.message());
        if(step!=before.completedStep()+1)throw new Problem(409,"out-of-order","Complete the preceding demonstration step first. Reset starts step 1.");
        var info=demo.ensure();
        // An interruption after any real operation must not make a retry overwrite partial work.
        var incomplete=new State(before.generation(),before.completedStep(),before.actor(),
            "Step "+step+" did not finish. Saved work is preserved. Reset to restart the demonstration.",before.conflicted(),true);
        save(incomplete,service.state(info.aliceWorkspaceId()),service.state(info.bobWorkspaceId()));
        try {
            String message=perform((int)step,actor,info);
            var alice=service.state(info.aliceWorkspaceId());var bob=service.state(info.bobWorkspaceId());
            String viewer=step==7?actor:step==2||step==4?"Alice":"Bob";
            var complete=new State(before.generation(),(int)step,viewer,message,conflicted(alice,bob),false);
            save(complete,alice,bob);return complete;
        } catch(Exception failure) {
            System.err.println("Demo step "+step+" failed: "+failure.getClass().getSimpleName()+": "+failure.getMessage());
            // Keep the previous completed stage, including when Record succeeded but Deliver did not.
            return state();
        }
    }
    private String perform(int step,String actor,DemoLifecycle.Info info) throws Exception {
        var alice=service.state(info.aliceWorkspaceId());var bob=service.state(info.bobWorkspaceId());
        switch(step){
            case 2 -> {
                requireFlow(alice,"10");requireFlow(bob,"10");
                alice=act(alice,"edit","attributeId",flow(alice).id(),"expression","12");requireFlow(alice,"12");
                return "Alice set flowRate to 12 in her local draft. Nothing published yet.";
            }
            case 3 -> {
                requireFlow(bob,"10");
                bob=act(bob,"edit","attributeId",flow(bob).id(),"expression","15");requireFlow(bob,"15");
                return "Bob set flowRate to 15 in his independent local draft. Nothing published yet.";
            }
            case 4 -> {
                alice=act(alice,"record","description","Alice sets flowRate to 12");
                alice=act(alice,"deliver","operationId",operation(info,step));
                requireFlow(alice,"12");require(alice.conflicts().isEmpty()&&!alice.dirty(),"Alice delivery did not finish");
                return "Alice recorded and published flowRate 12 to the shared model.";
            }
            case 5 -> {
                bob=act(bob,"record","description","Bob sets flowRate to 15");
                bob=act(bob,"deliver","operationId",operation(info,step));
                require(bob.conflicts().size()==1,"Bob delivery did not expose the expected conflict");
                var conflict=bob.conflicts().getFirst();
                require(conflict.target().attributeId().equals(flow(bob).id())&&scalar(conflict.base().value(),"10")&&scalar(conflict.incoming().value(),"12")&&scalar(conflict.local().value(),"15"),"Unexpected flowRate conflict");
                require(alice.acceptedHead().equals(bob.acceptedHead()),"Conflicted delivery changed accepted history");
                return "Bob recorded and published his pending change. Resolve base 10, Alice 12, and Bob 15. The shared model stays at 12.";
            }
            case 6 -> {
                require(bob.conflicts().size()==1,"The earlier flowRate conflict is missing");
                var conflict=bob.conflicts().getFirst();
                bob=act(bob,"add","elementId",pump(bob).id(),"name","alertEnabled","typeName","Boolean","expression","true");
                bob=act(bob,"record","description","Bob adds alertEnabled true");
                require(bob.conflicts().equals(List.of(conflict))&&bob.history().size()==2,"The earlier conflict or later change was lost");
                requireAlert(bob);
                return "Bob added and recorded alertEnabled true as a later change. The flowRate conflict remains.";
            }
            case 7 -> {
                require(bob.conflicts().size()==1,"The flowRate conflict is missing");requireAlert(bob);
                bob=service.resolveScenario(bob.workspaceId(),bob.conflicts().getFirst(),actor).state();
                requireFlow(bob,"14");requireAlert(bob);require(bob.conflicts().isEmpty(),"Resolution left conflicts");
                bob=act(bob,"deliver","operationId",operation(info,step));
                alice=act(alice,"update");
                for(var workspace:List.of(alice,bob)){
                    requireFlow(workspace,"14");requireAlert(workspace);
                    require(workspace.conflicts().isEmpty()&&!workspace.dirty()&&workspace.acceptedBaseRevision().equals(workspace.acceptedHead()),"Final integration did not finish");
                }
                return actor+" resolved flowRate to 14 and published Bob's changes. The later alertEnabled true was preserved. Alice and Bob are up to date.";
            }
            default -> throw new IllegalArgumentException("Unknown demonstration step");
        }
    }
    private WorkspaceData.State act(WorkspaceData.State state,String action,String... fields) throws Exception {
        var request=new JsonObject();request.addProperty("expectedRevision",state.revision());request.addProperty("documentVersion",state.source().version());
        for(int i=0;i<fields.length;i+=2)request.addProperty(fields[i],fields[i+1]);
        return service.execute(state.workspaceId(),action,request).state();
    }
    private void save(State state,WorkspaceData.State alice,WorkspaceData.State bob) throws Exception {
        store.metadata("scenario",WorkspaceService.JSON.toJson(new Progress(state,alice.revision(),bob.revision(),alice.acceptedHead())));
    }
    private static String operation(DemoLifecycle.Info info,int step){return "scenario-"+info.generation()+"-step-"+step;}
    private static boolean conflicted(WorkspaceData.State alice,WorkspaceData.State bob){return !alice.conflicts().isEmpty()||!bob.conflicts().isEmpty();}
    private static ModelElement pump(WorkspaceData.State state){
        var usages=state.analysis().elements().stream().filter(e->e.kind().equals("PartUsage")&&e.name().equals("pump")).toList();
        if(!usages.isEmpty()){require(usages.size()==1,"The demo needs one pump usage");return usages.getFirst();}
        return state.analysis().elements().stream().filter(e->e.kind().equals("PartDefinition")&&e.name().equals("Pump")).findFirst().orElseThrow(()->new IllegalStateException("The demo pump is missing"));
    }
    private static Attribute flow(WorkspaceData.State state){
        return pump(state).attributes().stream().filter(a->a.name().equals("flowRate")&&a.editable()&&!a.inherited()&&a.value().scalar()!=null&&a.value().scalar().kind()==ScalarKind.REAL).findFirst().orElseThrow(()->new IllegalStateException("The demo needs an authored Real pump.flowRate"));
    }
    private static void requireFlow(WorkspaceData.State state,String expected){require(scalar(flow(state).value(),expected),"Unexpected pump.flowRate");}
    private static void requireAlert(WorkspaceData.State state){
        require(pump(state).attributes().stream().anyMatch(a->a.name().equals("alertEnabled")&&a.value()!=null&&a.value().scalar()!=null&&a.value().scalar().kind()==ScalarKind.BOOLEAN&&scalar(a.value(),"true")),"The later alertEnabled true was not preserved");
    }
    private static boolean scalar(PropertyValue value,String expected){return value!=null&&value.scalar()!=null&&value.scalar().canonical().equals(expected);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
