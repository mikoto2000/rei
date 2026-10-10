package dev.mikoto2000.rei.storage;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sqlite.SQLiteConnection;

/** Offline migration snapshots: streamed files and SQLite Online Backup, never a DB-only copy. */
public final class StorageBackup {
  private StorageBackup() {}
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_ENTRIES = 1_000_000;
  private record Entry(String path, String kind, long size, String sha256) {}
  private record Snapshot(long entries, long sourceBytes, String fingerprint) {}
  @FunctionalInterface private interface Visitor { void accept(Path file, String kind, String sourceHash) throws IOException; }
  private static final class Accumulator {
    final byte[] sum = new byte[32]; long entries; long bytes;
    void add(String path, String kind, String hash, long size) throws IOException {
      if (++entries > MAX_ENTRIES) throw new IOException("Migration source entry limit exceeded");
      byte[] item = digest().digest((path + "\0" + kind + "\0" + size + "\0" + hash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      for (int i=0;i<sum.length;i++) sum[i]^=item[i];
      bytes = Math.addExact(bytes, size);
    }
    Snapshot snapshot() { return new Snapshot(entries, bytes, HexFormat.of().formatHex(sum)); }
  }
  static boolean hasSources(Path root) throws IOException { return scan(root, (file, kind, hash) -> {}).entries() != 0; }
  static void copy(Path root, Path backup) throws IOException {
    requireSafePath(root); requireSafePath(backup);
    Files.createDirectories(backup.resolve("files"));
    // A conservative preflight is advisory; every actual write still propagates ENOSPC.
    Snapshot before = scan(root, (file, kind, hash) -> {});
    if (Files.getFileStore(backup).getUsableSpace() < Math.addExact(Math.multiplyExact(before.sourceBytes(), 3), 64L*1024*1024))
      throw new IOException("Insufficient free space for verified backup and migration; source retained");
    Path manifest = backup.resolve("manifest.jsonl");
    try (var out = Files.newBufferedWriter(manifest, StandardOpenOption.CREATE_NEW)) {
      Snapshot copied = scan(root, (source, kind, sourceHash) -> {
        String relative = relative(root, source); Path target = inside(backup.resolve("files"), relative);
        Files.createDirectories(target.getParent());
        if (kind.equals("SQLITE")) onlineBackup(source, target);
        else Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        force(target);
        out.write(JSON.writeValueAsString(new Entry(relative, kind, Files.size(target), hash(target)))); out.newLine();
      });
      if (!before.equals(copied)) throw new IOException("Migration sources changed while copying");
      writeAtomic(backup.resolve("snapshot.json"), JSON.writeValueAsBytes(copied));
    }
    force(manifest);
  }
  static void verifyCopied(Path backup) throws IOException {
    requireSafePath(backup);
    Snapshot snapshot = JSON.readValue(metadata(backup.resolve("snapshot.json")), Snapshot.class);
    long[] count = {0};
    entries(backup, entry -> {
      Path file = inside(backup.resolve("files"), entry.path()); requireSafePath(file);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file)!=entry.size() || !hash(file).equals(entry.sha256()))
        throw new IOException("Backup hash/size verification failed: " + entry.path());
      if (entry.kind().equals("SQLITE")) integrity(file);
      else if (!entry.kind().equals("FILE")) throw new IOException("Unsupported backup entry kind");
      if (++count[0]>MAX_ENTRIES) throw new IOException("Backup entry limit exceeded");
    });
    if (count[0]!=snapshot.entries()) throw new IOException("Backup manifest count mismatch");
  }
  static void verifySources(Path root, Path backup) throws IOException {
    Snapshot expected = JSON.readValue(metadata(backup.resolve("snapshot.json")), Snapshot.class);
    if (!expected.equals(scan(root, (file, kind, hash) -> {}))) throw new IOException("Migration sources changed; stop other writers and retry");
  }
  static void markVerified(Path backup) throws IOException {
    writeAtomic(backup.resolve("verified.json"), JSON.writeValueAsBytes(Map.of(
        "manifest",hash(backup.resolve("manifest.jsonl")),"snapshot",hash(backup.resolve("snapshot.json")))));
  }
  public static void verify(Path backup) throws IOException {
    requireSafePath(backup);
    var marker = JSON.readTree(metadata(backup.resolve("verified.json")));
    if (!hash(backup.resolve("manifest.jsonl")).equals(marker.path("manifest").asText())
        || !hash(backup.resolve("snapshot.json")).equals(marker.path("snapshot").asText()))
      throw new IOException("Backup verification marker does not match manifest");
    verifyCopied(backup);
  }
  /** Restore only to an empty directory: never overwrite data created after a storage switch. */
  public static void restoreToEmptyDirectory(Path backup, Path target) throws IOException {
    verify(backup); target=target.toAbsolutePath().normalize(); requireSafePath(target);
    if(target.startsWith(backup.toAbsolutePath().normalize()))throw new IOException("Restore target must be outside the backup");
    Files.createDirectories(target);
    try (var children=Files.list(target)) { if(children.findAny().isPresent()) throw new IOException("Restore target must be empty"); }
    Path destination=target;
    entries(backup, entry -> {
      Path source=inside(backup.resolve("files"),entry.path()), restored=inside(destination,entry.path());
      requireSafePath(restored); Files.createDirectories(restored.getParent());
      Files.copy(source,restored,StandardCopyOption.COPY_ATTRIBUTES); force(restored);
      if(!hash(restored).equals(entry.sha256()))throw new IOException("Restored file hash mismatch");
      if(entry.kind().equals("SQLITE"))integrity(restored);
    });
  }
  private static Snapshot scan(Path root, Visitor visitor) throws IOException {
    requireSafePath(root); var total=new Accumulator();
    for(String name:List.of("sessions.json","projects.json","storage.db","memory.db","memory-consolidation.db","state","events","artifacts"))
      scanPath(root,root.resolve(name),visitor,total);
    Path projects=root.resolve("projects"); requireSafePath(projects);
    if(Files.exists(projects,LinkOption.NOFOLLOW_LINKS))try(var directories=Files.newDirectoryStream(projects)) {
      int count=0;
      for(Path project:directories) {
        if(++count>MAX_ENTRIES)throw new IOException("Project directory limit exceeded");
        requireSafePath(project);
        if(!Files.isDirectory(project,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unexpected project storage entry: "+project);
        try{UUID.fromString(project.getFileName().toString());}catch(IllegalArgumentException e){throw new IOException("Unknown project storage directory: "+project,e);}
        for(String name:List.of("state","events","artifacts"))scanPath(root,project.resolve(name),visitor,total);
      }
    }
    return total.snapshot();
  }
  private static void scanPath(Path root,Path source,Visitor visitor,Accumulator total)throws IOException {
    requireSafePath(source);
    if(Files.notExists(source,LinkOption.NOFOLLOW_LINKS)) {
      if(source.toString().endsWith(".db")&&(Files.exists(Path.of(source+"-wal"),LinkOption.NOFOLLOW_LINKS)||Files.exists(Path.of(source+"-shm"),LinkOption.NOFOLLOW_LINKS)))
        throw new IOException("Unresolved orphan SQLite WAL/SHM: "+source);
      return;
    }
    Files.walkFileTree(source,EnumSet.noneOf(FileVisitOption.class),64,new SimpleFileVisitor<>() {
      @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs)throws IOException {
        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Storage operation interrupted");
        requireSafePath(path); return FileVisitResult.CONTINUE;
      }
      @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws IOException {
        if(!attrs.isRegularFile()||attrs.isSymbolicLink())throw new IOException("Unsupported migration source or depth limit: "+path);
        if(path.toString().endsWith("-wal")||path.toString().endsWith("-shm")) {
          Path database=Path.of(path.toString().substring(0,path.toString().length()-4));requireSafePath(database);
          if(!Files.isRegularFile(database,LinkOption.NOFOLLOW_LINKS)||!isSqlite(database))throw new IOException("Unresolved orphan SQLite WAL/SHM: "+path);
          return FileVisitResult.CONTINUE;
        }
        requireSafePath(path);String kind=isSqlite(path)?"SQLITE":"FILE";
        String fingerprint=hash(path);long size=Files.size(path);
        if(kind.equals("SQLITE")) {
          Path wal=Path.of(path+"-wal");requireSafePath(wal);
          if(Files.exists(wal,LinkOption.NOFOLLOW_LINKS)){fingerprint+="/"+hash(wal);size=Math.addExact(size,Files.size(wal));}
        }
        total.add(relative(root,path),kind,fingerprint,size);visitor.accept(path,kind,fingerprint);return FileVisitResult.CONTINUE;
      }
    });
  }
  private static boolean isSqlite(Path file)throws IOException {
    requireSafePath(file);
    String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
    if(name.endsWith(".db")||name.endsWith(".sqlite")||name.endsWith(".sqlite3"))return true;
    try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
      return Arrays.equals(input.readNBytes(16),"SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
  }
  static Connection readOnly(Path database)throws SQLException { return DriverManager.getConnection("jdbc:sqlite:"+database.toUri().toASCIIString()+"?mode=ro"); }
  private static void onlineBackup(Path source,Path target)throws IOException {
    try(var connection=readOnly(source)) {
      int result=connection.unwrap(SQLiteConnection.class).getDatabase().backup("main",target.toString(),null,100,10,100);
      if(result!=0)throw new SQLException("SQLite Online Backup failed with code "+result);
    }catch(SQLException e){throw new IOException("Cannot make SQLite Online Backup: "+source,e);}
  }
  static void integrity(Path database)throws IOException {
    try(var connection=readOnly(database);var statement=connection.createStatement();var result=statement.executeQuery("PRAGMA integrity_check")) {
      if(!result.next()||!"ok".equals(result.getString(1))||result.next())throw new IOException("SQLite integrity verification failed: "+database);
    }catch(SQLException e){throw new IOException("Cannot verify SQLite backup: "+database,e);}
  }
  @FunctionalInterface private interface EntryVisitor { void accept(Entry entry)throws IOException; }
  private static void entries(Path backup,EntryVisitor visitor)throws IOException {
    requireSafePath(backup.resolve("manifest.jsonl"));
    try(var reader=Files.newBufferedReader(backup.resolve("manifest.jsonl"))) {
      var line=new StringBuilder();int c;
      while((c=reader.read())!=-1) {
        if(c=='\n'){if(!line.isEmpty())visitor.accept(JSON.readValue(line.toString(),Entry.class));line.setLength(0);}
        else{if(line.length()>=65536)throw new IOException("Backup manifest line exceeds limit");line.append((char)c);}
      }
      if(!line.isEmpty())throw new IOException("Incomplete backup manifest");
    }
  }
  static void requireSafePath(Path supplied)throws IOException {
    Path path=supplied.toAbsolutePath().normalize();
    for(Path current=path;current!=null;current=current.getParent()) {
      if(Files.notExists(current,LinkOption.NOFOLLOW_LINKS))continue;
      var attrs=Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
      if(attrs.isSymbolicLink()||attrs.isOther())throw new IOException("Links/special paths are not permitted in migration: "+current);
    }
  }
  private static Path inside(Path root,String relative)throws IOException {
    Path path=Path.of(relative),resolved=root.resolve(path).normalize();
    if(path.isAbsolute()||relative.isBlank()||!resolved.startsWith(root)||resolved.equals(root))throw new IOException("Unsafe backup manifest path");
    return resolved;
  }
  private static String relative(Path root,Path path){return root.relativize(path).toString().replace('\\','/');}
  static String hash(Path file)throws IOException {
    requireSafePath(file);var digest=digest();byte[] bytes=new byte[65536];
    try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){int n;while((n=stream.read(bytes))!=-1){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Storage operation interrupted");digest.update(bytes,0,n);}}
    return HexFormat.of().formatHex(digest.digest());
  }
  private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
  static byte[] metadata(Path path)throws IOException {
    requireSafePath(path);
    try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes=input.readNBytes(65537);
      if(bytes.length>65536)throw new IOException("Backup metadata exceeds limit: "+path.getFileName());
      return bytes;
    }
  }
  static void force(Path path)throws IOException{try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}}
  static void writeAtomic(Path target,byte[] bytes)throws IOException {
    requireSafePath(target);Files.createDirectories(target.getParent());Path temporary=Files.createTempFile(target.getParent(),"migration-",".tmp");
    try{Files.write(temporary,bytes);force(temporary);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    finally{Files.deleteIfExists(temporary);}
  }
}
