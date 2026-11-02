package app;

import static app.ModelContracts.*;
import static app.WorkspaceData.*;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Single local coordinator. Parser and publication are deliberately serialized initially. */
public final class WorkspaceService implements AutoCloseable {
 public static final Gson JSON=new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
 private final WorkspaceStore store;private final GitStore git;private final ParserAdapter parser;
 private final ReplayMaterializer materializer;private final PublicationCoordinator publications;
 private final DemoLifecycle demo;private final DemoScenario scenario;
 private final FileChannel ownerChannel;private final FileLock ownerLock;
 public WorkspaceService(Path data,Path libraries,Path fixture) throws Exception {
  Files.createDirectories(data);
  ownerChannel=FileChannel.open(data.resolve("owner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
  FileLock acquired;
  try{acquired=ownerChannel.tryLock();}catch(OverlappingFileLockException e){ownerChannel.close();throw new IllegalStateException("This data directory is already open");}
  if(acquired==null){ownerChannel.close();throw new IllegalStateException("This data directory is already open");}ownerLock=acquired;
  store=new WorkspaceStore(data.resolve("workspaces.sqlite"));git=new GitStore(data.resolve("project.git"));
  parser=new ParserAdapter(libraries,data.resolve("analysis"));materializer=new ReplayMaterializer(parser,git);
  publications=new PublicationCoordinator(store,git,stage->{if(stage.equals(System.getProperty("sysml.failAt")))Runtime.getRuntime().halt(86);},json->rows(JSON.fromJson(json,WorkspaceData.class)));
  String reference=Files.readString(fixture);
  demo=new DemoLifecycle(store,git,parser,reference);scenario=new DemoScenario(this,store,demo);
  demo.recover();publications.recover();
  if(git.head(GitStore.MAIN)==null){
   var source=new SourceDocument("model.sysml",0,reference);
   var result=parser.analyze("initial-system",0,List.of(source),Map.of());ReplayMaterializer.requireValid(result.analysis());
   String initial=git.commit(null,files(source,result.identities()),"System","Initial accepted system");
   if(!git.advance(GitStore.MAIN,initial,null))throw new IllegalStateException("Initial accepted head raced");
  }
 }
 public synchronized Map<String,Object> project() throws Exception {
  demo.recover();
  var workspaces=store.all().stream().map(row->{var w=decode(row);return Map.of("workspaceId",w.workspaceId(),"actor",w.actor());}).toList();
  var drafts=new ArrayList<Map<String,Object>>();
  for(String ref:git.draftRefs()){
   var draft=JSON.fromJson(git.read(git.head(ref),"draft.json"),SharedDraft.class);
   drafts.add(Map.of("id",draft.id(),"actor",draft.actor(),"description",draft.description(),"conflictCount",draft.state().conflicts().size()));
  }
  var project=new LinkedHashMap<String,Object>();project.put("acceptedHead",git.head(GitStore.MAIN));project.put("acceptedHistory",git.acceptedHistory());
  project.put("workspaces",workspaces);project.put("drafts",drafts);project.put("demo",demo.info());return project;
 }
 public record DemoState(String generation,State alice,State bob) {}
 public synchronized DemoState demo() throws Exception {
  var info=demo.ensure();return demoState(info);
 }
 public synchronized DemoScenario.State scenario() throws Exception {publications.recover();return scenario.state();}
 public synchronized DemoScenario.State scenarioStep(String expectedGeneration,long step,String actor) throws Exception {publications.recover();return scenario.step(expectedGeneration,step,actor);}
 public synchronized DemoState resetDemo(String expectedGeneration,String operation) throws Exception {
  operationId(operation);
  return demoState(demo.reset(expectedGeneration,operation));
 }
 private DemoState demoState(DemoLifecycle.Info info) throws Exception {
  return new DemoState(info.generation(),state(info.aliceWorkspaceId()),state(info.bobWorkspaceId()));
 }
 public synchronized Response create(String actor) throws Exception {
  demo.recover();publications.recover();
  if(actor==null||actor.isBlank()||actor.length()>40||actor.chars().anyMatch(Character::isISOControl))throw new Problem(400,"invalid","Use an actor name between 1 and 40 characters");
  String id=UUID.randomUUID().toString(),head=git.head(GitStore.MAIN);
  var source=new SourceDocument("model.sysml",0,git.read(head,"model.sysml"));
  var result=parser.analyze(id,0,List.of(source),identities(head));
  var w=new WorkspaceData(id,actor,0,head,source,result.identities(),result.analysis(),result.analysis(),List.of(),List.of(),false,0);
  store.create(row(w));return response(w,"ok","Independent workspace created");
 }
 public synchronized State state(String id) throws Exception {demo.recover();return get(id).state(git.head(GitStore.MAIN));}
 public record SharedDraft(String id,String actor,String description,State state){}
 public synchronized SharedDraft draft(String id) throws Exception {
  demo.recover();operationId(id);String commit=git.head("refs/drafts/"+id);if(commit==null)throw new Problem(404,"missing","Draft not found");
  return JSON.fromJson(git.read(commit,"draft.json"),SharedDraft.class);
 }
 public synchronized Response execute(String id,String action,JsonObject request) throws Exception {
  demo.recover();publications.recover();
  if(action.equals("deliver")||action.equals("share")){
   String op=string(request,"operationId");operationId(op);
   String done=store.operation(op,id,action);if(done!=null)return JSON.fromJson(done,Response.class);
  }
  var w=get(id);if(number(request,"expectedRevision")!=w.revision())throw new Problem(409,"stale","Workspace changed. Refresh before retrying this action.");
  try{return switch(action){
   case "save"->save(w,request);
   case "edit","add"->graphEdit(w,request,action);
   case "record"->record(w,string(request,"description"));
   case "deliver"->deliver(w,string(request,"operationId"));
   case "update"->update(w);
   case "resolve"->resolve(w,request);
   case "share"->share(w,string(request,"operationId"),string(request,"description"));
   default->throw new Problem(404,"missing","Unknown operation");
  };}catch(Problem e){throw e;}catch(IllegalArgumentException e){throw new Problem(422,"unsupported",e.getMessage());}
 }
 private Response save(WorkspaceData w,JsonObject request) throws Exception {
  checkDocument(w,request);
  var source=new SourceDocument(w.source().documentId(),w.source().version()+1,string(request,"text"));
  var result=parser.analyze(w.workspaceId(),w.revision()+1,List.of(source),w.identities());
  if(result.analysis().state()==AnalysisState.VALID){
   for(var c:w.conflicts()){
    var old=find(w.analysis(),c.target().attributeId());var next=find(result.analysis(),c.target().attributeId());
    if(old!=null&&next!=null&&!Objects.equals(old.value(),next.value()))throw new Problem(409,"needs-resolution","Use the explicit conflict resolution controls for this property.");
   }
  }
  var next=changed(w,source,result.identities(),result.analysis(),w.history(),w.conflicts(),!source.text().equals(tipSource(w).text()),w.baseCommit(),w.deliveredCount());
  persist(w,next);return response(next,"ok",result.analysis().state()==AnalysisState.VALID?"Saved":"Saved with diagnostics");
 }
 private Response graphEdit(WorkspaceData w,JsonObject request,String action) throws Exception {
  checkDocument(w,request);ReplayMaterializer.requireValid(w.analysis());SourceDocument candidate;
  if(action.equals("edit")){
   String id=string(request,"attributeId");
   if(w.conflicts().stream().anyMatch(c->c.target().attributeId().equals(id)))throw new Problem(409,"needs-resolution","Resolve this conflict explicitly");
   var a=find(w.analysis(),id);if(a==null||!a.editable())throw new Problem(422,"unsupported","Attribute is read-only or missing");
   candidate=SourceEdits.replace(w.source(),a,a.value().expression(),string(request,"expression"));
  }else{
   var owner=w.analysis().elements().stream().filter(e->e.id().equals(string(request,"elementId"))).findFirst().orElseThrow(()->new Problem(404,"missing","Block not found"));
   candidate=SourceEdits.addAttribute(w.source(),owner,string(request,"name"),string(request,"typeName"),string(request,"expression"));
  }
  var result=parser.analyze(w.workspaceId(),w.revision()+1,List.of(candidate),w.identities());
  ReplayMaterializer.requireValid(result.analysis());var edits=ChangeEngine.diff(w.analysis(),result.analysis());
  if(edits.size()>1)throw new Problem(422,"unsupported","A graphical edit must change only its target scalar property");
  if(edits.size()==1&&action.equals("edit")&&!edits.getFirst().target().attributeId().equals(string(request,"attributeId")))throw new Problem(422,"unsupported","Unexpected graphical edit target");
  var next=changed(w,candidate,result.identities(),result.analysis(),w.history(),w.conflicts(),!candidate.text().equals(tipSource(w).text()),w.baseCommit(),w.deliveredCount());
  persist(w,next);return response(next,"ok","Property updated");
 }
 private Response record(WorkspaceData w,String description) throws Exception {
  ReplayMaterializer.requireValid(w.analysis());if(!w.dirty())throw new Problem(409,"unchanged","There are no unrecorded changes");
  if(description.isBlank()||description.length()>200)throw new Problem(400,"invalid","Describe the change in 1 to 200 characters");
  var before=materializer.analyze(w.workspaceId(),w.revision(),tipSource(w),w.identities());
  var edits=ChangeEngine.diff(before.analysis(),w.analysis());
  var semantic=materializer.patch(w.workspaceId(),w.revision()+1,before,edits);
  SourceScope.requireOnlyTriviaOrLiteralSpelling(semantic.source().text(),semantic.analysis(),w.source().text(),w.analysis());
  String id=UUID.randomUUID().toString(),revision=UUID.randomUUID().toString();
  String parent=w.history().size()>w.deliveredCount()?w.history().getLast().change().revisionId():w.baseCommit();
  var change=new ChangeRevision(id,revision,parent,w.actor(),description,edits,List.of(),List.of(),w.conflicts().stream().map(Conflict::id).toList(),List.of());
  var history=new ArrayList<>(w.history());
  history.add(new Frame(change,before.source(),semantic.source().text(),w.source().text(),w.source(),w.identities(),metadata(w.analysis(),w.revision()+1),w.conflicts()));
  var next=changed(w,w.source(),w.identities(),w.analysis(),history,w.conflicts(),false,w.baseCommit(),w.deliveredCount());
  persist(w,next);return response(next,"ok","Change recorded");
 }
 private Response update(WorkspaceData w) throws Exception {
  requireClean(w);String head=git.head(GitStore.MAIN);
  if(head.equals(w.baseCommit()))return response(w,"ok","Already based on the accepted system");
  var next=replay(w,head,null,null);persist(w,next);
  return response(next,next.conflicts().isEmpty()?"ok":"needs-resolution","Updated from accepted history without dropping local changes");
 }
 private Response deliver(WorkspaceData w,String operation) throws Exception {
  requireClean(w);if(w.history().size()==w.deliveredCount())throw new Problem(409,"unchanged","Record a change before delivery");
  String head=git.head(GitStore.MAIN);var rebased=replay(w,head,null,null);
  if(!rebased.conflicts().isEmpty()){
   persist(w,rebased);return response(rebased,"needs-resolution","Delivery completed with conflicts. Accepted history is unchanged.");
  }
  ReplayMaterializer.requireValid(rebased.analysis());
  String commit=git.commit(head,files(rebased.source(),rebased.identities()),w.actor(),"Deliver "+w.actor()+" changes");
  var accepted=new WorkspaceData(rebased.workspaceId(),rebased.actor(),rebased.revision(),commit,rebased.source(),rebased.identities(),rebased.analysis(),rebased.lastValidAnalysis(),rebased.history(),List.of(),false,rebased.history().size());
  var result=new Response("ok","Delivered to the accepted system",accepted.state(commit));
  publications.publish(new WorkspaceStore.Intent(operation,w.workspaceId(),"deliver",w.revision(),GitStore.MAIN,head,commit,JSON.toJson(accepted),JSON.toJson(result)),rows(accepted));
  return result;
 }
 private Response share(WorkspaceData w,String operation,String description) throws Exception {
  requireClean(w);if(w.history().size()==w.deliveredCount())throw new Problem(409,"unchanged","Record a draft before sharing");
  if(description.isBlank())description="Draft by "+w.actor();
  var next=changed(w,w.source(),w.identities(),w.analysis(),w.history(),w.conflicts(),false,w.baseCommit(),w.deliveredCount());
  String head=git.head(GitStore.MAIN);var shared=new SharedDraft(operation,w.actor(),description,next.state(head));
  var files=new LinkedHashMap<>(files(w.source(),w.identities()));files.put("draft.json",JSON.toJson(shared));
  String commit=git.commit(w.baseCommit(),files,w.actor(),"Share "+description);
  var result=new Response("ok","Shared an immutable draft; accepted history is unchanged",next.state(head));
  publications.publish(new WorkspaceStore.Intent(operation,w.workspaceId(),"share",w.revision(),"refs/drafts/"+operation,null,commit,JSON.toJson(next),JSON.toJson(result)),rows(next));
  return result;
 }
 private Response resolve(WorkspaceData w,JsonObject request) throws Exception {return resolveAs(w,request,w.actor());}
 synchronized Response resolveScenario(String id,Conflict conflict,String actor) throws Exception {
  if(!"Alice".equals(actor)&&!"Bob".equals(actor))throw new Problem(400,"invalid","Choose Alice or Bob as the resolver");
  var request=new JsonObject();request.addProperty("originChangeId",conflict.originChangeId());request.addProperty("conflictId",conflict.id());request.addProperty("expression","14");
  return resolveAs(get(id),request,actor);
 }
 private Response resolveAs(WorkspaceData w,JsonObject request,String actor) throws Exception {
  requireClean(w);String changeId=string(request,"originChangeId"),conflictId=string(request,"conflictId");
  var frame=w.history().subList(w.deliveredCount(),w.history().size()).stream().filter(f->f.change().changeId().equals(changeId)).findFirst().orElseThrow(()->new Problem(404,"missing","Conflict origin not found"));
  var conflict=frame.conflicts().stream().filter(c->c.id().equals(conflictId)&&c.originChangeId().equals(changeId)).findFirst().orElseThrow(()->new Problem(409,"stale","This conflict is no longer present at its origin"));
  var attribute=find(frame.analysis(),conflict.target().attributeId());if(attribute==null)throw new Problem(422,"unsupported","The conflicted property has no authored mapping");
  var source=SourceEdits.replace(frame.materialized(),attribute,attribute.value().expression(),string(request,"expression"));
  var proposal=parser.analyze(w.workspaceId(),w.revision()+1,List.of(source),frame.identities());ReplayMaterializer.requireValid(proposal.analysis());
  var value=find(proposal.analysis(),attribute.id());if(value==null||!value.editable())throw new Problem(422,"unsupported","Resolution must be a supported scalar value");
  var next=replay(w,w.baseCommit(),conflict,value.value(),actor);persist(w,next);
  return response(next,next.conflicts().isEmpty()?"ok":"needs-resolution","Origin resolved and descendant changes reapplied");
 }
 private WorkspaceData replay(WorkspaceData w,String base,Conflict resolved,PropertyValue resolution) throws Exception {return replay(w,base,resolved,resolution,w.actor());}
 private WorkspaceData replay(WorkspaceData w,String base,Conflict resolved,PropertyValue resolution,String resolutionActor) throws Exception {
  long revision=w.revision()+1;
  var ids=new LinkedHashMap<>(identities(base));
  for(var f:w.history())f.identities().forEach(ids::putIfAbsent);
  var source=new SourceDocument("model.sysml",w.source().version()+1,git.read(base,"model.sysml"));
  var current=materializer.analyze(w.workspaceId(),revision,source,ids);
  var history=new ArrayList<>(w.history().subList(0,w.deliveredCount()));
  var aliases=new HashMap<String,String>();List<Conflict> conflicts=List.of();String parent=base,incomingActor=git.author(base);
  for(var old:w.history().subList(w.deliveredCount(),w.history().size())){
   var edits=remap(old.change().edits(),aliases);
   boolean resolving=resolved!=null&&old.change().changeId().equals(resolved.originChangeId());
   if(resolving)edits=ChangeEngine.resolveEdits(edits,resolved,resolution);
   var merged=ChangeEngine.apply(edits,current.analysis(),conflicts,old.change().changeId(),old.change().parentRevisionId(),parent,old.change().revisionId(),old.change().actor());
   aliases.putAll(merged.identityRemap());edits=remap(edits,aliases);
   var adjusted=new LinkedHashMap<>(current.identities());adjusted.replaceAll((k,v)->aliases.getOrDefault(v,v));
   current=new ReplayMaterializer.Snapshot(current.source(),current.analysis(),adjusted);
   current=materializer.patch(w.workspaceId(),revision,current,merged.patches());
   current=materializer.preserveSource(w.workspaceId(),revision,current,old.semanticSource(),old.authoredSource());
   ReplayMaterializer.requireValid(current.analysis());
   var updatedConflicts=new ArrayList<Conflict>();
   for(var c:merged.conflicts()){
    if(c.originChangeId().equals(old.change().changeId()))c=new Conflict(c.id(),c.originChangeId(),c.target(),c.kind(),new ConflictAlternative(c.base().revisionId(),"Base",c.base().value()),new ConflictAlternative(c.incoming().revisionId(),incomingActor,c.incoming().value()),c.local());
    updatedConflicts.add(c);
   }
   conflicts=List.copyOf(updatedConflicts);String changeRevision=UUID.randomUUID().toString();
   var resolutions=new ArrayList<>(old.change().resolutions());if(resolving)resolutions.add(new Resolution(resolved.id(),old.change().revisionId(),resolutionActor,resolution));
   var owned=conflicts.stream().filter(c->c.originChangeId().equals(old.change().changeId())).toList();
   var inherited=conflicts.stream().filter(c->!c.originChangeId().equals(old.change().changeId())).map(Conflict::id).toList();
   var change=new ChangeRevision(old.change().changeId(),changeRevision,parent,old.change().actor(),old.change().description(),edits,old.change().sourcePatches(),owned,inherited,resolutions);
   history.add(new Frame(change,old.sourceBase(),old.semanticSource(),old.authoredSource(),current.source(),current.identities(),current.analysis(),conflicts));
   parent=changeRevision;incomingActor=old.change().actor();
  }
  return changed(w,current.source(),current.identities(),current.analysis(),history,conflicts,false,base,w.deliveredCount());
 }
 private static List<AuthoredEdit> remap(List<AuthoredEdit> edits,Map<String,String> aliases){
  return edits.stream().map(e->new AuthoredEdit(e.kind(),e.documentId(),new PropertyTarget(e.target().elementId(),aliases.getOrDefault(e.target().attributeId(),e.target().attributeId())),e.attributeName(),e.before(),e.after())).toList();
 }
 private SourceDocument tipSource(WorkspaceData w) throws Exception {
  return w.history().size()>w.deliveredCount()?w.history().getLast().materialized():new SourceDocument("model.sysml",w.source().version(),git.read(w.baseCommit(),"model.sysml"));
 }
 private WorkspaceData changed(WorkspaceData w,SourceDocument source,Map<String,String>ids,Analysis analysis,List<Frame>history,List<Conflict>conflicts,boolean dirty,String base,int delivered){
  analysis=metadata(analysis,w.revision()+1);var last=analysis.state()==AnalysisState.VALID?analysis:w.lastValidAnalysis();
  return new WorkspaceData(w.workspaceId(),w.actor(),w.revision()+1,base,source,ids,analysis,last,history,conflicts,dirty,delivered);
 }
 private static Analysis metadata(Analysis a,long revision){return new Analysis(a.workspaceId(),revision,a.documentVersions(),a.state(),a.elements(),a.diagnostics(),a.connections());}
 private void persist(WorkspaceData old,WorkspaceData next) throws Exception {store.save(row(next),old.revision(),rows(next));}
 private static WorkspaceStore.Row row(WorkspaceData w){return new WorkspaceStore.Row(w.workspaceId(),w.revision(),JSON.toJson(w));}
 private static List<WorkspaceStore.Row> rows(WorkspaceData w){return w.history().stream().map(f->new WorkspaceStore.Row(f.change().revisionId(),0,JSON.toJson(f))).toList();}
 private WorkspaceData get(String id) throws Exception {
  var stored=store.get(id);if(stored==null)throw new Problem(404,"missing","Workspace not found");
  var w=decode(stored);
  if(w.analysisVersion()<WorkspaceData.CURRENT_ANALYSIS_VERSION){
   var result=parser.analyze(w.workspaceId(),w.revision(),List.of(w.source()),w.identities());
   var latest=result.analysis().state()==AnalysisState.VALID?result.analysis():w.lastValidAnalysis();
   w=new WorkspaceData(w.workspaceId(),w.actor(),w.revision(),w.baseCommit(),w.source(),result.identities(),
     result.analysis(),latest,w.history(),w.conflicts(),w.dirty(),w.deliveredCount());
   store.save(row(w),stored.revision(),rows(w));
  }
  return w;
 }
 private static WorkspaceData decode(WorkspaceStore.Row row){return JSON.fromJson(row.json(),WorkspaceData.class);}
 private Response response(WorkspaceData w,String status,String message) throws Exception{return new Response(status,message,w.state(git.head(GitStore.MAIN)));}
 private static Attribute find(Analysis analysis,String id){return analysis.elements().stream().flatMap(e->e.attributes().stream()).filter(a->a.id().equals(id)).findFirst().orElse(null);}
 private Map<String,String> identities(String commit) throws Exception{return JSON.fromJson(git.read(commit,"identities.json"),new TypeToken<Map<String,String>>(){}.getType());}
 private static Map<String,String> files(SourceDocument source,Map<String,String>ids){return Map.of("model.sysml",source.text(),"identities.json",JSON.toJson(ids));}
 private static void requireClean(WorkspaceData w){if(w.dirty())throw new Problem(409,"dirty","Record unrelated source edits before changing history or delivery");}
 private static void checkDocument(WorkspaceData w,JsonObject body){if(number(body,"documentVersion")!=w.source().version())throw new Problem(409,"stale","Source document changed");}
 public static String string(JsonObject body,String key){var e=body.get(key);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new Problem(400,"invalid","Expected string field: "+key);return e.getAsString();}
 public static long number(JsonObject body,String key){try{return body.get(key).getAsBigDecimal().longValueExact();}catch(Exception e){throw new Problem(400,"invalid","Expected integer field: "+key);}}
 private static void operationId(String id){if(!id.matches("[a-zA-Z0-9-]{1,80}"))throw new Problem(400,"invalid","Invalid operation ID");}
 @Override public synchronized void close() throws Exception {try{parser.close();store.close();}finally{ownerLock.release();ownerChannel.close();}}
}
