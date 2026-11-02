package app;
import static app.ModelContracts.*;
import java.util.*;
/** Applies engine proposals to exact source through current parser mappings. */
public final class ReplayMaterializer {
 private final ParserAdapter parser;private final GitStore git;
 public record Snapshot(SourceDocument source,Analysis analysis,Map<String,String> identities){}
 public ReplayMaterializer(ParserAdapter parser,GitStore git){this.parser=parser;this.git=git;}
 public Snapshot analyze(String workspace,long revision,SourceDocument source,Map<String,String> ids){
  var result=parser.analyze(workspace,revision,List.of(source),ids);
  return new Snapshot(source,result.analysis(),result.identities());
 }
 public Snapshot patch(String workspace,long revision,Snapshot start,List<AuthoredEdit> edits){
  var current=start;
  for(var edit:edits){
   var owner=current.analysis().elements().stream().filter(e->e.id().equals(edit.target().elementId())).findFirst()
       .orElseThrow(()->new IllegalArgumentException("The edited block no longer exists"));
   var attribute=owner.attributes().stream().filter(a->a.id().equals(edit.target().attributeId())||a.name().equals(edit.attributeName())).findFirst().orElse(null);
   SourceDocument source=attribute==null
      ? SourceEdits.addAttribute(current.source(),owner,edit.attributeName(),shortType(edit.after().typeName()),edit.after().expression())
      : SourceEdits.replace(current.source(),attribute,attribute.value().expression(),edit.after().expression());
   current=analyze(workspace,revision,source,current.identities());
   requireValid(current.analysis());
  }
  return current;
 }
 public Snapshot preserveSource(String workspace,long revision,Snapshot candidate,String semanticSource,String authoredSource) throws Exception{
  String merged=git.mergeText(semanticSource,authoredSource,candidate.source().text());
  if(merged.equals(candidate.source().text()))return candidate;
  return analyze(workspace,revision,new SourceDocument(candidate.source().documentId(),candidate.source().version()+1,merged),candidate.identities());
 }
 public static void requireValid(Analysis analysis){
  if(analysis.state()!=AnalysisState.VALID)throw new Problem(422,"invalid","The official parser reports errors. Fix the source before recording or delivery.");
 }
 private static String shortType(String type){int pos=type.lastIndexOf("::");return pos<0?type:type.substring(pos+2);}
}
