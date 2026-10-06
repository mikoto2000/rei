package dev.mikoto2000.rei.skills;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Single bounded snapshot in the existing application DB; schema initialized on first opted-in use. */
public final class SqliteSkillEmbeddingIndex implements SkillEmbeddingIndex {
  private final DataSource data;
  public SqliteSkillEmbeddingIndex(DataSource data){this.data=Objects.requireNonNull(data);}
  @Override public Map<String,float[]> load(String namespace,List<String> profiles) {
    validateNamespace(namespace);if(profiles.size()>256)throw new IllegalArgumentException("Skill index profile limit exceeded");
    var requested=new HashMap<String,String>();
    for(var profile:profiles){validateProfile(profile);requested.put(hash(profile.getBytes(StandardCharsets.UTF_8)),profile);}
    if(requested.isEmpty())return Map.of();
    RunCancellation.propagate(null);
    try(var connection=data.getConnection()) {
      initialize(connection);
      try(var statement=connection.prepareStatement("""
          SELECT substr(profile_sha,1,65) AS profile_sha,dimension,length(vector) AS bytes,
                 substr(vector,1,32769) AS vector,substr(vector_sha,1,65) AS vector_sha
          FROM skill_embedding_index WHERE namespace=? LIMIT 257
          """)) {
        statement.setQueryTimeout(5);statement.setString(1,namespace);
        var result=new LinkedHashMap<String,float[]>();int rows=0;
        try(var rs=statement.executeQuery()) {
          while(rs.next()) {
            RunCancellation.propagate(null);if(++rows>256)return Map.of();
            String profile=requested.get(rs.getString("profile_sha"));if(profile==null)continue;
            int dimension=rs.getInt("dimension");long bytes=rs.getLong("bytes");
            if(dimension<1||dimension>8192||bytes!=(long)dimension*4)continue;
            byte[] blob=rs.getBytes("vector");if(blob==null||blob.length!=bytes||!hash(blob).equals(rs.getString("vector_sha")))continue;
            var vector=new float[dimension];var buffer=ByteBuffer.wrap(blob);
            for(int i=0;i<dimension;i++)vector[i]=buffer.getFloat();
            if(valid(vector))result.put(profile,vector);
          }
        }
        return Map.copyOf(result);
      }
    }catch(SQLException error){throw new IllegalStateException("Skill embedding index unavailable",error);}
  }
  @Override public void replace(String namespace,Map<String,float[]> vectors) {
    validateNamespace(namespace);if(vectors.size()>256)throw new IllegalArgumentException("Skill index vector limit exceeded");
    record Row(String profile,int dimension,byte[] vector,String checksum) {}
    var rows=new ArrayList<Row>();
    for(var entry:vectors.entrySet()) {
      validateProfile(entry.getKey());if(!valid(entry.getValue()))throw new IllegalArgumentException("Invalid skill index vector");
      var buffer=ByteBuffer.allocate(entry.getValue().length*4);for(float value:entry.getValue())buffer.putFloat(value);
      var blob=buffer.array();rows.add(new Row(hash(entry.getKey().getBytes(StandardCharsets.UTF_8)),entry.getValue().length,blob,hash(blob)));
    }
    RunCancellation.propagate(null);
    try(var connection=data.getConnection()) {
      initialize(connection);connection.setAutoCommit(false);
      try {
        try(var delete=connection.createStatement()){delete.setQueryTimeout(5);delete.executeUpdate("DELETE FROM skill_embedding_index");}
        try(var insert=connection.prepareStatement("INSERT INTO skill_embedding_index(profile_sha,namespace,dimension,vector,vector_sha) VALUES(?,?,?,?,?)")) {
          insert.setQueryTimeout(5);
          for(var row:rows){RunCancellation.propagate(null);insert.setString(1,row.profile());insert.setString(2,namespace);
            insert.setInt(3,row.dimension());insert.setBytes(4,row.vector());insert.setString(5,row.checksum());insert.addBatch();}
          insert.executeBatch();
        }
        RunCancellation.propagate(null);connection.commit();
      }catch(SQLException|RuntimeException error){connection.rollback();throw error;}
      finally{connection.setAutoCommit(true);}
    }catch(SQLException error){throw new IllegalStateException("Skill embedding index update unavailable",error);}
  }
  @Override public void invalidate(String namespace) {
    validateNamespace(namespace);RunCancellation.propagate(null);
    try(var connection=data.getConnection()) {
      initialize(connection);
      try(var statement=connection.prepareStatement("DELETE FROM skill_embedding_index WHERE namespace=?")) {
        statement.setQueryTimeout(5);statement.setString(1,namespace);statement.executeUpdate();
      }
    }catch(SQLException error){throw new IllegalStateException("Skill embedding index invalidation unavailable",error);}
  }
  private static void initialize(Connection connection)throws SQLException {
    try(var statement=connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS skill_embedding_index(
            profile_sha TEXT PRIMARY KEY,namespace TEXT NOT NULL,dimension INTEGER NOT NULL,
            vector BLOB NOT NULL,vector_sha TEXT NOT NULL)
          """);
    }
  }
  private static boolean valid(float[] vector) {
    if(vector==null||vector.length<1||vector.length>8192)return false;
    double norm=0;for(float value:vector){if(!Float.isFinite(value))return false;norm+=(double)value*value;}
    return norm==0||Math.abs(norm-1)<0.0001;
  }
  private static void validateNamespace(String namespace) {
    if(namespace==null||!namespace.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("Invalid skill index namespace");
  }
  private static void validateProfile(String profile) {
    if(profile==null||profile.length()>2048)throw new IllegalArgumentException("Invalid skill index profile");
  }
  private static String hash(byte[] bytes) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
  }
}
