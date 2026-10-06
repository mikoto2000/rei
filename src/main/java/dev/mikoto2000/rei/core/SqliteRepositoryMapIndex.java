package dev.mikoto2000.rei.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** One bounded metadata snapshot, initialized only on explicit opted-in use. */
public final class SqliteRepositoryMapIndex {
  public record Entry(String path,String digest,String packageName,List<RepositoryMapService.Symbol> symbols,List<String> imports) {
    public Entry { symbols=List.copyOf(symbols);imports=List.copyOf(imports); }
  }
  private static final ObjectMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder(
      JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(2048).build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final String PROFILE=hash(("repository-java-ast-v1/"+System.getProperty("java.vendor")+"/"+System.getProperty("java.version")).getBytes(StandardCharsets.UTF_8));
  private final DataSource data;
  public SqliteRepositoryMapIndex(DataSource data){this.data=Objects.requireNonNull(data);}
  public Map<String,Entry> load(Path root) {
    RunCancellation.propagate(null);
    try(var connection=data.getConnection()) {
      initialize(connection);
      try(var statement=connection.prepareStatement("SELECT length(payload),substr(payload,1,65537),substr(payload_sha,1,65) FROM repository_map_index WHERE root_sha=? AND profile=? LIMIT 1025")) {
        statement.setQueryTimeout(5);statement.setString(1,rootHash(root));statement.setString(2,PROFILE);
        var entries=new LinkedHashMap<String,Entry>();long total=0;int rows=0;
        try(var rs=statement.executeQuery()) {
          while(rs.next()) {
            RunCancellation.propagate(null);long size=rs.getLong(1);if(++rows>1024||size<1||size>65536||(total+=size)>8*1024*1024)return Map.of();
            byte[] bytes=rs.getBytes(2);if(bytes==null||bytes.length!=size||!hash(bytes).equals(rs.getString(3)))continue;
            try {var entry=JSON.readValue(bytes,Entry.class);validate(entry);if(entries.putIfAbsent(entry.path(),entry)!=null)return Map.of();}
            catch(java.io.IOException|RuntimeException invalid){RunCancellation.propagate(invalid);}
          }
        }
        return Map.copyOf(entries);
      }
    }catch(SQLException error){throw new IllegalStateException("Repository metadata index unavailable",error);}
  }
  public void replace(Path root,Collection<Entry> entries) {
    if(entries.size()>1024)throw new IllegalArgumentException("Repository metadata entry limit exceeded");
    record Row(String path,byte[] bytes,String checksum) {}
    var rows=new ArrayList<Row>();long total=0;var paths=new HashSet<String>();
    for(var entry:entries) {
      RunCancellation.propagate(null);validate(entry);if(!paths.add(entry.path()))throw new IllegalArgumentException("Duplicate repository path");
      try {byte[] bytes=JSON.writeValueAsBytes(entry);if(bytes.length>65536||(total+=bytes.length)>8*1024*1024)throw new IllegalArgumentException("Repository metadata byte limit exceeded");rows.add(new Row(entry.path(),bytes,hash(bytes)));}
      catch(java.io.IOException error){throw new IllegalArgumentException("Invalid repository metadata",error);}
    }
    RunCancellation.propagate(null);
    try(var connection=data.getConnection()) {
      initialize(connection);connection.setAutoCommit(false);
      try {
        try(var delete=connection.createStatement()){delete.setQueryTimeout(5);delete.executeUpdate("DELETE FROM repository_map_index");}
        try(var insert=connection.prepareStatement("INSERT INTO repository_map_index(root_sha,profile,path,payload,payload_sha) VALUES(?,?,?,?,?)")) {
          insert.setQueryTimeout(5);
          for(var row:rows){RunCancellation.propagate(null);insert.setString(1,rootHash(root));insert.setString(2,PROFILE);insert.setString(3,row.path());insert.setBytes(4,row.bytes());insert.setString(5,row.checksum());insert.addBatch();}insert.executeBatch();
        }
        RunCancellation.propagate(null);connection.commit();
      }catch(SQLException|RuntimeException error){connection.rollback();throw error;}finally{connection.setAutoCommit(true);}
    }catch(SQLException error){throw new IllegalStateException("Repository metadata index update unavailable",error);}
  }
  private static void initialize(Connection connection)throws SQLException {
    try(var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.executeUpdate("CREATE TABLE IF NOT EXISTS repository_map_index(root_sha TEXT NOT NULL,profile TEXT NOT NULL,path TEXT PRIMARY KEY,payload BLOB NOT NULL,payload_sha TEXT NOT NULL)");}
  }
  private static void validate(Entry entry) {
    if(entry==null||!text(entry.path(),1024)||!entry.path().endsWith(".java")||entry.path().contains("\\")||entry.digest()==null||!entry.digest().matches("[a-f0-9]{64}")||entry.packageName()==null||entry.packageName().length()>512||entry.symbols().size()>64||entry.imports().size()>128)throw new IllegalArgumentException("Invalid repository metadata");
    Path path=Path.of(entry.path());if(path.isAbsolute()||!path.normalize().equals(path)||path.startsWith("..")||RepositoryMapService.sensitive(path))throw new IllegalArgumentException("Invalid repository path");
    for(var symbol:entry.symbols())if(symbol==null||!text(symbol.name(),2048)||!Set.of("CLASS","INTERFACE","ENUM","RECORD","ANNOTATION_TYPE","METHOD").contains(symbol.kind())||symbol.line()<1||symbol.entryPoint()&&!symbol.kind().equals("METHOD"))throw new IllegalArgumentException("Invalid repository symbol");
    for(var imported:entry.imports())if(!text(imported,2048))throw new IllegalArgumentException("Invalid repository import");
  }
  private static boolean text(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max&&value.codePoints().noneMatch(Character::isISOControl);}
  private static String rootHash(Path root){try{return hash(root.toRealPath().toString().getBytes(StandardCharsets.UTF_8));}catch(java.io.IOException error){throw new IllegalArgumentException("Repository root unavailable",error);}}
  private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
}
