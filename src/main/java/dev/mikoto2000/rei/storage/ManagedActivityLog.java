package dev.mikoto2000.rei.storage;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.event.ProfileEventLogEntry;

/** New diagnostic segments only. Existing activity.jsonl is never adopted or mutated. */
public final class ManagedActivityLog {
  private record Segment(String id,String scope,String path,long bytes,String sha256,Instant created) {}
  private final StorageObjectRegistry registry;private final long segmentBytes;
  public ManagedActivityLog(StorageObjectRegistry registry){this(registry,4L*1024*1024);}
  ManagedActivityLog(StorageObjectRegistry registry,long bytes){if(bytes<1024||bytes>4L*1024*1024)throw new IllegalArgumentException("Segment budget required");this.registry=registry;segmentBytes=bytes;}
  private static String scope(String supplied){return supplied==null?"":UUID.fromString(supplied).toString();}
  private Path directory(String scope){return (scope.isEmpty()?registry.root:registry.root.resolve("projects").resolve(scope)).resolve("logs/activity-archives");}
  public Path legacyFile(String supplied){return directory(scope(supplied)).getParent().resolve("activity.jsonl");}
  private Path path(Segment segment)throws Exception {
    Path file=registry.root.resolve(segment.path()).normalize();if(!file.getParent().equals(directory(segment.scope()))||!file.getFileName().toString().equals(segment.id()+".jsonl"))throw new IllegalStateException("Invalid segment identity");StorageBackup.requireSafePath(file);return file;
  }
  private static Segment open(Connection db,String scope)throws Exception {
    try(var query=db.prepareStatement("SELECT * FROM activity_segments WHERE scope=? AND state='OPEN'")){query.setString(1,scope);try(var rows=query.executeQuery()){return rows.next()?new Segment(rows.getString("id"),scope,rows.getString("path"),rows.getLong("bytes"),rows.getString("sha256"),Instant.parse(rows.getString("created"))):null;}}
  }
  private void verify(Segment segment)throws Exception {
    Path file=path(segment);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=segment.bytes())throw new IllegalStateException("Open Activity segment changed or interrupted; preserve it for inspection");
  }
  private static String chain(String previous,byte[] record)throws Exception {var digest=java.security.MessageDigest.getInstance("SHA-256");digest.update(HexFormat.of().parseHex(previous));return HexFormat.of().formatHex(digest.digest(record));}
  private static String verifyChain(Path file)throws Exception {
    String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest());
    try(var input=new BufferedInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS),65536)){var line=new ByteArrayOutputStream();int value;while((value=input.read())!=-1){if(line.size()>1024*1024)throw new IOException("Activity line limit exceeded");line.write(value);if(value=='\n'){hash=chain(hash,line.toByteArray());line.reset();}}if(line.size()!=0)throw new IOException("Incomplete Activity tail");}return hash;
  }
  private void close(Connection db,Segment segment)throws Exception {
    verify(segment);Path file=path(segment);if(!verifyChain(file).equals(segment.sha256()))throw new IllegalStateException("Activity content changed; source remains unmanaged and protected");StorageBackup.force(file);
    StorageObjectRegistry.put(db,registry.root,file,"ACTIVITY_RAW",segment.scope(),null,null,registry.clock.instant(),true,List.of());
    // Every Session/Run association is retained conservatively, including unknown owners.
    forEachFile(file,entry->{try {
      if(entry.sessionId()!=null)StorageObjectRegistry.addReference(db,StorageObjectRegistry.id(registry.root,file),"SESSION_HISTORY",entry.sessionId());
      if(entry.runId()!=null)StorageObjectRegistry.addReference(db,StorageObjectRegistry.id(registry.root,file),"RUN_HISTORY",entry.runId());
    }catch(Exception error){throw new IllegalStateException("Cannot protect Activity owner",error);}});
    try(var update=db.prepareStatement("UPDATE activity_segments SET state='CLOSED',updated=? WHERE id=?")){update.setString(1,registry.clock.instant().toString());update.setString(2,segment.id());update.executeUpdate();}
  }
  public void closeSegment(String supplied){String scope=scope(supplied);registry.database.transaction(db->{var segment=open(db,scope);if(segment!=null)close(db,segment);return null;});}
  public void append(String supplied,ProfileEventLogEntry entry){Objects.requireNonNull(entry);String scope=scope(supplied);registry.database.transaction(db->{
    byte[] record=(StorageDatabase.JSON.writeValueAsString(entry)+"\n").getBytes(StandardCharsets.UTF_8);if(record.length>1024*1024)throw new IllegalArgumentException("Activity record exceeds 1 MiB");
    var segment=open(db,scope);if(segment!=null){verify(segment);if(segment.bytes()+record.length>segmentBytes||!segment.created().plus(Duration.ofDays(1)).isAfter(registry.clock.instant())){close(db,segment);segment=null;}}
    if(segment==null) {
      String id=UUID.randomUUID().toString();Path file=directory(scope).resolve(id+".jsonl");StorageBackup.requireSafePath(file);Files.createDirectories(file.getParent());Files.createFile(file);StorageBackup.force(file);
      String relative=registry.root.relativize(file).toString().replace('\\','/'),hash=StorageBackup.hash(file),now=registry.clock.instant().toString();
      try(var insert=db.prepareStatement("INSERT INTO activity_segments VALUES(?,?,?,'OPEN',0,?,?,?)")){insert.setString(1,id);insert.setString(2,scope);insert.setString(3,relative);insert.setString(4,hash);insert.setString(5,now);insert.setString(6,now);insert.executeUpdate();}
      segment=new Segment(id,scope,relative,0,hash,registry.clock.instant());
    }
    Path file=path(segment);Files.write(file,record,StandardOpenOption.APPEND);StorageBackup.force(file);
    try(var update=db.prepareStatement("UPDATE activity_segments SET bytes=?,sha256=?,updated=? WHERE id=?")){update.setLong(1,segment.bytes()+record.length);update.setString(2,chain(segment.sha256(),record));update.setString(3,registry.clock.instant().toString());update.setString(4,segment.id());update.executeUpdate();}return null;
  });}
  /** Bounded materializing compatibility API; never returns a silently incomplete list. */
  public List<ProfileEventLogEntry> read(String supplied){String scope=scope(supplied);return registry.serialized(()->registry.database.read(db->{
    var result=new ArrayList<ProfileEventLogEntry>();long[] bytes={0};Path parent=directory(scope),legacy=parent.getParent().resolve("activity.jsonl");
    java.util.function.Consumer<Path> add=file->{try {
      StorageBackup.requireSafePath(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Unsupported Activity body");bytes[0]=Math.addExact(bytes[0],Files.size(file));if(bytes[0]>32L*1024*1024)throw new IllegalStateException("Activity read exceeds 32 MiB; use a narrower retained history");
      if(file.getParent().equals(parent)) {
        var object=StorageObjectRegistry.find(db,StorageObjectRegistry.id(registry.root,file));
        if(object.isPresent()){if(!object.get().originVerified()||!registry.unchanged(object.get()))throw new IllegalStateException("Activity archive is changed or unverified");}
        else try(var query=db.prepareStatement("SELECT * FROM activity_segments WHERE path=? AND scope=? AND state='OPEN'")){query.setString(1,registry.root.relativize(file).toString().replace('\\','/'));query.setString(2,scope);try(var rows=query.executeQuery()){if(!rows.next()||Files.size(file)!=rows.getLong("bytes")||!verifyChain(file).equals(rows.getString("sha256")))throw new IllegalStateException("Activity archive is unmanaged or interrupted; retained for inspection");}}
      }
      forEachFile(file,entry->{if(result.size()>=100000)throw new IllegalStateException("Activity read exceeds 100,000 rows");result.add(entry);});
    }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("Cannot read Activity body",error);}};
    if(Files.exists(legacy,LinkOption.NOFOLLOW_LINKS))add.accept(legacy);
    StorageBackup.requireSafePath(parent);if(Files.exists(parent,LinkOption.NOFOLLOW_LINKS))try(var files=Files.newDirectoryStream(parent)) {
      int count=0;for(Path file:files){if(++count>10000)throw new IllegalStateException("Activity file scan budget exceeded");String name=file.getFileName().toString();if(!name.endsWith(".jsonl"))throw new IllegalStateException("Unknown Activity archive");UUID.fromString(name.replaceFirst("\\.jsonl$",""));
        var object=StorageObjectRegistry.find(db,StorageObjectRegistry.id(registry.root,file));if(object.isPresent()&&!object.get().status().equals("AVAILABLE"))continue;add.accept(file);
      }
    }
    result.sort(Comparator.comparing(ProfileEventLogEntry::timestamp).thenComparing(ProfileEventLogEntry::id));return List.copyOf(result);
  }));}
  private static void forEachFile(Path file,java.util.function.Consumer<ProfileEventLogEntry> consumer)throws Exception {
    var mapper=StorageDatabase.JSON.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    try(var input=new BufferedInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS),65536)) {
      var line=new ByteArrayOutputStream();int value;long count=0;while((value=input.read())!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Activity read interrupted");if(value!='\n'){if(line.size()>=1024*1024)throw new IOException("Activity line exceeds 1 MiB");line.write(value);continue;}
        if(line.size()>0){String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(line.toByteArray())).toString();if(!text.isBlank()){if(++count>1000000)throw new IOException("Activity row scan budget exceeded");consumer.accept(mapper.readValue(text,ProfileEventLogEntry.class));}}line.reset();
      }if(line.size()!=0)throw new IOException("Incomplete Activity tail retained");
    }
  }
}
