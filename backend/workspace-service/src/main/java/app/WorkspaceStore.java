package app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** SQLite is the authority for complete workspace snapshots, not for accepted Git refs. */
public final class WorkspaceStore implements AutoCloseable {
    private final Connection db;
    public record Row(String id, long revision, String json) {}
    public record Intent(String id, String workspaceId, String action, long expectedRevision,
                         String ref, String oldHead, String candidate, String workspaceJson, String responseJson) {}
    public WorkspaceStore(Path path) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (var s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL"); s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS workspaces(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, json TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS revisions(id TEXT PRIMARY KEY, json TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS intents(id TEXT PRIMARY KEY, workspaceId TEXT NOT NULL, action TEXT NOT NULL, expectedRevision INTEGER NOT NULL, ref TEXT NOT NULL, oldHead TEXT, candidate TEXT NOT NULL, workspaceJson TEXT NOT NULL, responseJson TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY, workspaceId TEXT NOT NULL, action TEXT NOT NULL, json TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS demo_metadata(key TEXT PRIMARY KEY, json TEXT NOT NULL)");
        }
    }
    public synchronized Row get(String id) throws SQLException {
        try (var s = db.prepareStatement("SELECT revision,json FROM workspaces WHERE id=?")) {
            s.setString(1,id); try (var r=s.executeQuery()) { return r.next()?new Row(id,r.getLong(1),r.getString(2)):null; }
        }
    }
    public synchronized List<Row> all() throws SQLException {
        var rows=new ArrayList<Row>();
        try (var s=db.createStatement();var r=s.executeQuery("SELECT id,revision,json FROM workspaces ORDER BY id")) {
            while(r.next()) rows.add(new Row(r.getString(1),r.getLong(2),r.getString(3)));
        } return rows;
    }
    public synchronized void create(Row row) throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO workspaces VALUES(?,?,?)")) {
            s.setString(1,row.id());s.setLong(2,row.revision());s.setString(3,row.json());s.executeUpdate();
        }
    }
    public synchronized void save(Row row,long expected,List<Row> revisions) throws SQLException {
        transaction(() -> { replace(row,expected); retain(revisions); });
    }
    private void replace(Row row,long expected) throws SQLException {
        try(var s=db.prepareStatement("UPDATE workspaces SET revision=?,json=? WHERE id=? AND revision=?")) {
            s.setLong(1,row.revision());s.setString(2,row.json());s.setString(3,row.id());s.setLong(4,expected);
            if(s.executeUpdate()!=1) throw new IllegalStateException("stale workspace revision");
        }
    }
    private void retain(List<Row> revisions) throws SQLException {
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO revisions VALUES(?,?)")) {
            for(var row:revisions){s.setString(1,row.id());s.setString(2,row.json());s.addBatch();}s.executeBatch();
        }
    }
    public synchronized String revision(String id) throws SQLException {
        try(var s=db.prepareStatement("SELECT json FROM revisions WHERE id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()){return r.next()?r.getString(1):null;}
        }
    }
    public synchronized String operation(String id,String workspace,String action) throws SQLException {
        try(var s=db.prepareStatement("SELECT workspaceId,action,json FROM operations WHERE id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()){
                if(!r.next())return null;
                if(!workspace.equals(r.getString(1))||!action.equals(r.getString(2)))throw new IllegalArgumentException("Operation ID already belongs to another request");
                return r.getString(3);
            }
        }
    }
    public synchronized void prepare(Intent i) throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO intents VALUES(?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,i.id());s.setString(2,i.workspaceId());s.setString(3,i.action());s.setLong(4,i.expectedRevision());
            s.setString(5,i.ref());s.setString(6,i.oldHead());s.setString(7,i.candidate());s.setString(8,i.workspaceJson());s.setString(9,i.responseJson());s.executeUpdate();
        }
    }
    public synchronized List<Intent> pending() throws SQLException {
        var values=new ArrayList<Intent>();try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM intents ORDER BY rowid")){
            while(r.next())values.add(new Intent(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9)));
        }return values;
    }
    public synchronized void finish(Intent i,Row replacement,List<Row> revisions) throws SQLException {
        transaction(() -> {
            replace(replacement,i.expectedRevision());retain(revisions);
            try(var s=db.prepareStatement("INSERT INTO operations VALUES(?,?,?,?)")){
                s.setString(1,i.id());s.setString(2,i.workspaceId());s.setString(3,i.action());s.setString(4,i.responseJson());s.executeUpdate();
            }
            try(var s=db.prepareStatement("DELETE FROM intents WHERE id=?")){s.setString(1,i.id());s.executeUpdate();}
        });
    }
    public synchronized void abort(String id) throws SQLException {
        try(var s=db.prepareStatement("DELETE FROM intents WHERE id=?")){s.setString(1,id);s.executeUpdate();}
    }
    public synchronized String metadata(String key) throws SQLException {
        try(var s=db.prepareStatement("SELECT json FROM demo_metadata WHERE key=?")){
            s.setString(1,key);try(var result=s.executeQuery()){return result.next()?result.getString(1):null;}
        }
    }
    public synchronized void metadata(String key,String json) throws SQLException {
        try(var s=db.prepareStatement("INSERT OR REPLACE INTO demo_metadata VALUES(?,?)")){
            s.setString(1,key);s.setString(2,json);s.executeUpdate();
        }
    }
    public synchronized void installDemo(String metadata,Row alice,Row bob,boolean clear) throws SQLException {
        transaction(()->{
            if(clear)try(var s=db.createStatement()){
                s.executeUpdate("DELETE FROM workspaces");s.executeUpdate("DELETE FROM revisions");
                s.executeUpdate("DELETE FROM intents");s.executeUpdate("DELETE FROM operations");
                s.executeUpdate("DELETE FROM demo_metadata");
            }
            create(alice);create(bob);metadata("demo",metadata);
        });
    }
    private interface Write {void run() throws SQLException;}
    private void transaction(Write operation) throws SQLException {
        db.setAutoCommit(false);
        try{operation.run();db.commit();}
        catch(SQLException|RuntimeException e){db.rollback();throw e;}
        finally{db.setAutoCommit(true);}
    }
    @Override public synchronized void close() throws SQLException {db.close();}
}
