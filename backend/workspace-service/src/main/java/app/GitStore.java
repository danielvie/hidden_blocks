package app;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Uses Git objects and refs, never a shared mutable working tree. */
public final class GitStore {
    public static final String MAIN="refs/heads/main";
    private final Path repository;
    private record Output(int status,String text) {}
    public GitStore(Path repository) throws Exception {
        this.repository=repository.toAbsolutePath();
        Files.createDirectories(this.repository.getParent());
        if(!Files.exists(this.repository.resolve("HEAD"))) {
            var p=new ProcessBuilder("git","init","--bare","--object-format=sha1",this.repository.toString()).redirectErrorStream(true).start();
            String out=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            if(p.waitFor()!=0)throw new IllegalStateException(out);
        }
    }
    public Path path(){return repository;}
    private Output execute(String input,Map<String,String> env,String...args) throws Exception {
        var command=new ArrayList<String>(List.of("git","--git-dir="+repository));
        command.addAll(List.of(args));
        var pb=new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT","0");pb.environment().putAll(env);
        var p=pb.start();
        try(var stdin=p.getOutputStream()){if(input!=null)stdin.write(input.getBytes(StandardCharsets.UTF_8));}
        String out=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if(!p.waitFor(30,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalStateException("Git timed out");}
        return new Output(p.exitValue(),out);
    }
    private String checked(String input,Map<String,String>env,String...args) throws Exception {
        var output=execute(input,env,args);
        if(output.status()!=0)throw new IllegalStateException("Git "+args[0]+" failed: "+output.text());
        return output.text();
    }
    public String head(String ref) throws Exception {
        checkRef(ref);
        var o=execute(null,Map.of(),"rev-parse","--verify",ref);
        if(o.status()!=0)return null;
        return oid(o.text().strip());
    }
    public boolean advance(String ref,String candidate,String expected) throws Exception {
        checkRef(ref);oid(candidate);if(expected!=null)oid(expected);
        return execute(null,Map.of(),"update-ref",ref,candidate,expected==null?"0".repeat(40):expected).status()==0;
    }
    public void retain(String operation,String commit) throws Exception {
        if(!operation.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("Invalid operation ID");
        checked(null,Map.of(),"update-ref","refs/pending/"+operation,oid(commit));
    }
    public String commit(String parent,Map<String,String>files,String actor,String description) throws Exception {
        var tree=new StringBuilder();
        for(var entry:new TreeMap<>(files).entrySet()){
            file(entry.getKey());
            String blob=oid(checked(entry.getValue(),Map.of(),"hash-object","-w","--stdin").strip());
            tree.append("100644 blob ").append(blob).append('\t').append(entry.getKey()).append('\n');
        }
        String treeId=oid(checked(tree.toString(),Map.of(),"mktree").strip());
        var args=new ArrayList<String>(List.of("commit-tree",treeId));
        if(parent!=null){args.add("-p");args.add(oid(parent));}
        if(actor==null||actor.isBlank()||actor.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid actor");
        var env=Map.of("GIT_AUTHOR_NAME",actor,"GIT_AUTHOR_EMAIL","demo@localhost","GIT_COMMITTER_NAME","SysML collaboration","GIT_COMMITTER_EMAIL","demo@localhost");
        return oid(checked(description+"\n",env,args.toArray(String[]::new)).strip());
    }
    public String read(String commit,String file) throws Exception {
        file(file);return checked(null,Map.of(),"show",oid(commit)+":"+file);
    }
    public String author(String commit) throws Exception {
        return checked(null,Map.of(),"show","-s","--format=%an",oid(commit)).strip();
    }
    public boolean ancestor(String ancestor,String descendant) throws Exception {
        return execute(null,Map.of(),"merge-base","--is-ancestor",oid(ancestor),oid(descendant)).status()==0;
    }
    public record AcceptedEntry(String revisionId,String parentRevisionId,String actor,String description) {}
    public List<AcceptedEntry> acceptedHistory() throws Exception {
        if(head(MAIN)==null)return List.of();
        String log=checked(null,Map.of(),"log","--format=%H%x00%P%x00%an%x00%s",MAIN);
        var entries=new ArrayList<AcceptedEntry>();
        for(String line:log.lines().toList()){
            String[] fields=line.split("\\u0000",-1);
            if(fields.length!=4)throw new IllegalStateException("Invalid accepted history output");
            entries.add(new AcceptedEntry(fields[0],fields[1].isEmpty()?null:fields[1],fields[2],fields[3]));
        }
        return entries;
    }
    /** Explicit disposable-demo reset only. Accepted history is otherwise append-only. */
    public void resetDemoHistory(String root) throws Exception {
        oid(root);
        String refs=checked(null,Map.of(),"for-each-ref","--format=%(refname) %(objectname)");
        var transaction=new StringBuilder("start\n");
        boolean hadMain=false;
        for(String line:refs.lines().toList()){
            String[] fields=line.split(" ",2);String ref=fields[0];
            checkRef(ref); // Refuse to clear unexpected user-managed refs.
            if(ref.equals(MAIN)){transaction.append("update ").append(MAIN).append(" ").append(root).append(" ").append(fields[1]).append("\n");hadMain=true;}
            else transaction.append("delete ").append(ref).append(" ").append(fields[1]).append("\n");
        }
        if(!hadMain)transaction.append("update ").append(MAIN).append(" ").append(root).append(" ").append("0".repeat(40)).append("\n");
        transaction.append("prepare\ncommit\n");
        checked(transaction.toString(),Map.of(),"update-ref","--stdin");
    }
    public List<String> draftRefs() throws Exception {
        String output=checked(null,Map.of(),"for-each-ref","--format=%(refname)","refs/drafts/");
        return output.lines().filter(s->!s.isBlank()).toList();
    }
    public String mergeText(String base,String local,String incoming) throws Exception {
        if(local.equals(base))return incoming;
        if(incoming.equals(base)||incoming.equals(local))return local;
        Path dir=Files.createTempDirectory(repository.getParent(),"merge-");
        try{
            Path current=dir.resolve("incoming"),old=dir.resolve("base"),other=dir.resolve("local");
            Files.writeString(current,incoming);Files.writeString(old,base);Files.writeString(other,local);
            var result=execute(null,Map.of(),"merge-file","-p",current.toString(),old.toString(),other.toString());
            if(result.status()!=0)throw new IllegalArgumentException("Overlapping source edits need manual reconciliation. Original text and accepted history were preserved.");
            return result.text();
        }finally{
            try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
    private static String oid(String value){
        if(!value.matches("[a-f0-9]{40}"))throw new IllegalArgumentException("Invalid Git object ID");return value;
    }
    private static void checkRef(String ref){
        if(!ref.equals(MAIN)&&!ref.matches("refs/(drafts|pending)/[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("Invalid managed ref");
    }
    private static void file(String file){
        if(!Set.of("model.sysml","identities.json","draft.json").contains(file))throw new IllegalArgumentException("Invalid repository file");
    }
}
