package app;
import static app.ModelContracts.*;
import java.util.List;import java.util.Map;

public record WorkspaceData(String workspaceId,String actor,long revision,String baseCommit,
 SourceDocument source,Map<String,String> identities,Analysis analysis,Analysis lastValidAnalysis,
 List<Frame> history,List<Conflict> conflicts,boolean dirty,int deliveredCount,int analysisVersion) {
 public static final int CURRENT_ANALYSIS_VERSION=2;
 public WorkspaceData(String workspaceId,String actor,long revision,String baseCommit,SourceDocument source,
  Map<String,String> identities,Analysis analysis,Analysis lastValidAnalysis,List<Frame> history,List<Conflict> conflicts,boolean dirty,int deliveredCount){
  this(workspaceId,actor,revision,baseCommit,source,identities,analysis,lastValidAnalysis,history,conflicts,dirty,deliveredCount,CURRENT_ANALYSIS_VERSION);
 }
 public WorkspaceData { identities=Map.copyOf(identities);history=List.copyOf(history);conflicts=List.copyOf(conflicts); }
 public record Frame(ChangeRevision change,SourceDocument sourceBase,String semanticSource,String authoredSource,
                     SourceDocument materialized,Map<String,String> identities,Analysis analysis,List<Conflict> conflicts){
  public Frame{identities=Map.copyOf(identities);conflicts=List.copyOf(conflicts);}
 }
 public record HistoryEntry(String changeId,String revisionId,String parentRevisionId,String actor,String description,
                            String text,List<Conflict> conflicts,List<String> inheritedConflictIds,boolean accepted){}
 public record State(String workspaceId,String actor,long revision,String acceptedBaseRevision,String acceptedHead,
                     SourceDocument source,Analysis analysis,Analysis lastValidAnalysis,List<Conflict> conflicts,
                     boolean dirty,List<HistoryEntry> history){}
 public record Response(String status,String message,State state){}
 public State state(String acceptedHead){
  return new State(workspaceId,actor,revision,baseCommit,acceptedHead,source,analysis,lastValidAnalysis,conflicts,dirty,
    java.util.stream.IntStream.range(0,history.size()).mapToObj(i->{var f=history.get(i);return new HistoryEntry(f.change().changeId(),f.change().revisionId(),f.change().parentRevisionId(),
      f.change().actor(),f.change().description(),f.materialized().text(),f.conflicts(),f.change().inheritedConflictIds(),i<deliveredCount);}).toList());
 }
}
