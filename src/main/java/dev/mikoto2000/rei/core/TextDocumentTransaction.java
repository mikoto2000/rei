package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Logical all-or-rollback text application. No filesystem promises atomic visibility of multiple paths. */
public final class TextDocumentTransaction {
  public record Change(String path,String before,String after) {}
  public record Stage(String path,String temporary,String sha256,boolean baseline) {}
  public record Outcome(String status,List<String> warnings) {}
  public record Fingerprint(boolean available,boolean exists,String sha256) {}
  @FunctionalInterface public interface Move{void replace(Path staged,Path target)throws IOException;}
  @FunctionalInterface public interface Journal{void save(String phase,List<Stage> stages)throws IOException;}
  private TextDocumentTransaction() {}
  public static void preflight(Path root,String id,List<Change> changes)throws IOException{validate(root,id,changes);for(var stage:planned(changes,id))path(root,stage.temporary());for(var stage:planned(changes,id+"-rollback"))path(root,stage.temporary());}
  public static void replace(Path staged,Path target)throws IOException{if(staged==null)Files.delete(target);else Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
  /** Existing staging and permission preservation, with no non-atomic fallback. */
  public static void writeSingle(Path target,String before,String after)throws IOException{writeSingle(target,before,after,TextDocumentTransaction::replace);}
  static void writeSingle(Path target,String before,String after,Move move)throws IOException {
    text(before);text(after);Path root=target.toAbsolutePath().getParent().toRealPath();String name=target.getFileName().toString();
    if(!path(root,name).equals(target.toAbsolutePath().normalize()))throw new IOException("Aliased text target rejected");
    if(!Objects.equals(read(root,name),before))throw new IOException("Baseline changed before staging");
    var staged=new Stage(name,temporary(name,UUID.randomUUID().toString(),0),hash(after),false);
    try{
      RunCancellation.propagate(null);stage(root,staged,after);RunCancellation.propagate(null);
      if(!Objects.equals(read(root,name),before))throw new IOException("Baseline changed before atomic publication");
      if(!Objects.equals(read(root,staged.temporary()),after))throw new IOException("Staged content changed");
      move.replace(path(root,staged.temporary()),path(root,name));
    }catch(IOException|RuntimeException error){try{cleanup(root,List.of(staged));}catch(IOException|RuntimeException cleanup){error.addSuppressed(cleanup);}throw error;}
    cleanup(root,List.of(staged));
  }
  public static Outcome apply(Path root,String id,List<Change> changes,Journal journal,Move move)throws IOException{
    validate(root,id,changes);if(!matches(root,changes,false))return new Outcome("STALE",List.of("Baseline changed before staging; no target write"));
    var stages=new ArrayList<>(planned(changes,id));stages.addAll(planned(changes,id+"-rollback"));journal.save("STAGING",List.copyOf(stages));
    try{
      for(var stage:stages){RunCancellation.propagate(null);var change=changes.stream().filter(item->item.path().equals(stage.path())).findFirst().orElseThrow();stage(root,stage,stage.baseline()?change.before():change.after());}
      if(!matches(root,changes,false)){cleanup(root,stages);return new Outcome("STALE",List.of("Baseline changed before publication; no target write"));}
      for(var change:changes){
        RunCancellation.propagate(null);journal.save("PUBLISHING",stages);var target=path(root,change.path());if(!Objects.equals(read(root,change.path()),change.before()))throw new IOException("Baseline changed during publication");
        var stage=stages.stream().filter(item->!item.baseline()&&item.path().equals(change.path())).findFirst().orElse(null);Path temporary=stage==null?null:path(root,stage.temporary());if(stage!=null&&!stage.sha256().equals(hash(read(root,stage.temporary()))))throw new IOException("Staged text changed");
        move.replace(temporary,target);if(!Objects.equals(read(root,change.path()),change.after()))throw new IOException("Publication requires inspection");
      }
      if(!matches(root,changes,true))throw new IOException("Final text set differs");journal.save("VERIFIED",List.copyOf(stages));return new Outcome("APPLIED",List.of());
    }catch(IOException|RuntimeException failure){
      boolean interrupted=Thread.interrupted();Outcome recovered;
      try{recovered=rollback(root,id,changes,stages,journal);}catch(IOException|RuntimeException recovery){failure.addSuppressed(recovery);recovered=new Outcome("UNKNOWN",List.of("Rollback or staging cleanup requires inspection"));}
      finally{if(interrupted)Thread.currentThread().interrupt();}
      RunCancellation.propagate(failure);return recovered;
    }
  }
  public static Outcome rollback(Path root,String id,List<Change> changes,List<Stage> previous,Journal journal)throws IOException{
    validate(root,id,changes);validateStages(id,changes,previous);var recovery=planned(changes,id+"-rollback");var all=new ArrayList<>(previous);all.addAll(recovery);journal.save("ROLLBACK",List.copyOf(all));boolean complete=true;
    for(int i=changes.size()-1;i>=0;i--){var change=changes.get(i);String current;
      try{current=read(root,change.path());}catch(IOException|IllegalArgumentException unavailable){complete=false;continue;}
      if(Objects.equals(current,change.before()))continue;
      if(!Objects.equals(current,change.after())){complete=false;continue;}
      Path target=path(root,change.path());
      try{
        if(change.before()==null){if(!Objects.equals(read(root,change.path()),change.after())){complete=false;continue;}Files.delete(target);}
        else{
          String name=temporary(change.path(),id+"-rollback",i);var restore=new Stage(change.path(),name,hash(change.before()),true);Path temporary=path(root,name);
          if(Files.exists(temporary,LinkOption.NOFOLLOW_LINKS)){if(!Objects.equals(read(root,name),change.before())){complete=false;continue;}}else stage(root,restore,change.before());
          if(!Objects.equals(read(root,change.path()),change.after())){complete=false;continue;}replace(temporary,target);
        }
      }catch(IOException|IllegalArgumentException unavailable){complete=false;}
    }
    if(!matches(root,changes,false))complete=false;try{cleanup(root,all);}catch(IOException|IllegalArgumentException unavailable){complete=false;}
    journal.save(complete?"ROLLED_BACK":"UNKNOWN",complete?List.of():List.copyOf(all));return new Outcome(complete?"ROLLED_BACK":"UNKNOWN",List.of(complete?"Original text set restored; consumed attempt is not replayable":"Some current paths differ from both saved versions; preserve external changes and inspect"));
  }
  private static List<Stage> planned(List<Change> changes,String id){var stages=new ArrayList<Stage>();boolean baseline=id.endsWith("-rollback");for(int i=0;i<changes.size();i++){var change=changes.get(i);String text=baseline?change.before():change.after();if(text!=null)stages.add(new Stage(change.path(),temporary(change.path(),id,i),hash(text),baseline));}return List.copyOf(stages);}
  private static String temporary(String path,String id,int index){int slash=path.lastIndexOf('/');return (slash<0?"":path.substring(0,slash+1))+".rei-document-"+id+"-"+index+".tmp";}
  private static void stage(Path root,Stage stage,String text)throws IOException{
    Path temporary=path(root,stage.temporary()),target=path(root,stage.path());byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
    try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
    if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))copyPermissions(target,temporary);
  }
  private static void copyPermissions(Path source,Path destination)throws IOException{
    var posix=Files.getFileAttributeView(source,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);if(posix!=null){var attributes=posix.readAttributes();var target=Files.getFileAttributeView(destination,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);target.setPermissions(attributes.permissions());target.setGroup(attributes.group());target.setOwner(attributes.owner());}
    var acl=Files.getFileAttributeView(source,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);if(acl!=null){var target=Files.getFileAttributeView(destination,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);target.setAcl(acl.getAcl());if(!target.getOwner().equals(acl.getOwner()))target.setOwner(acl.getOwner());}
  }
  private static void cleanup(Path root,List<Stage> stages)throws IOException{var seen=new HashSet<String>();for(var stage:stages){if(!seen.add(stage.temporary()))continue;if(stage.temporary()==null||!stage.temporary().substring(stage.temporary().lastIndexOf('/')+1).startsWith(".rei-document-"))throw new IOException("Unowned staging path");Path path=path(root,stage.temporary());if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))continue;if(!stage.sha256().equals(hash(read(root,stage.temporary()))))throw new IOException("Staging content changed; preserve it");Files.delete(path);}}
  public static void cleanupOwned(Path root,String id,List<Change> changes,List<Stage> stages)throws IOException{validate(root,id,changes);validateStages(id,changes,stages);cleanup(root,stages);}
  private static void validateStages(String id,List<Change> changes,List<Stage> stages)throws IOException{var allowed=new HashSet<>(planned(changes,id));allowed.addAll(planned(changes,id+"-rollback"));if(stages==null||stages.size()>128||stages.stream().anyMatch(stage->!allowed.contains(stage)))throw new IOException("Staging journal differs from saved text set");}
  public static boolean matches(Path root,List<Change> changes,boolean after)throws IOException{for(var change:changes)if(!Objects.equals(read(root,change.path()),after?change.after():change.before()))return false;return true;}
  public static Path path(Path root,String name)throws IOException{
    if(root==null||!Files.isDirectory(root)||!root.toRealPath().equals(root)||name==null||name.isBlank()||name.length()>1024||name.contains("\\")||name.contains(":")||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Canonical Project root and relative text path required");
    Path relative=Path.of(name);if(relative.isAbsolute()||relative.getRoot()!=null||Arrays.stream(name.split("/",-1)).anyMatch(part->part.isBlank()||part.equals(".")||part.equals(".."))||RepositoryMapService.sensitive(relative))throw new IllegalArgumentException("Excluded or outside text path");
    Path cursor=root;for(var part:relative){cursor=cursor.resolve(part);if(Files.exists(cursor,LinkOption.NOFOLLOW_LINKS)){if(Files.isSymbolicLink(cursor)||!cursor.toRealPath().equals(cursor))throw new IllegalArgumentException("Linked or aliased text path rejected");}else if(!cursor.equals(root.resolve(relative)))throw new IllegalArgumentException("Existing parent directory required");}
    if(Files.exists(cursor,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(cursor,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Regular text target required");return cursor;
  }
  public static String read(Path root,String name)throws IOException{Path path=path(root,name);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=stream.readNBytes(65537);if(bytes.length>65536)throw new IllegalArgumentException("Text exceeds 64KiB");for(byte value:bytes)if(value==0)throw new IllegalArgumentException("Binary text rejected");try{return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("UTF-8 text required");}}}
  public static Fingerprint fingerprint(Path root,String name){try{Path file=path(root,name);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new Fingerprint(true,false,null);try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=stream.readNBytes(65537);if(bytes.length>65536)return new Fingerprint(false,true,null);return new Fingerprint(true,true,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));}}catch(IOException|IllegalArgumentException unavailable){return new Fingerprint(false,false,null);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
  public static void text(String value){if(value==null||value.indexOf(0)>=0||value.getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("Valid UTF-8 text of at most 64KiB required");try{StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value));}catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("Valid Unicode required");}}
  public static String hash(String text){if(text==null)return null;try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
  private static void validate(Path root,String id,List<Change> changes)throws IOException{if(id==null||!id.matches("[a-f0-9-]{36}")||changes==null||changes.isEmpty()||changes.size()>64)throw new IllegalArgumentException("Bounded transaction ID and changes required");int total=0;var names=new HashSet<String>();for(var change:changes){path(root,change.path());if(!names.add(change.path().toLowerCase(Locale.ROOT))||change.before()==null&&change.after()==null||Objects.equals(change.before(),change.after()))throw new IllegalArgumentException("Unique actual text changes required");for(String value:new String[]{change.before(),change.after()})if(value!=null){text(value);total+=value.getBytes(StandardCharsets.UTF_8).length;}}if(total>1048576)throw new IllegalArgumentException("Transaction text exceeds 1MiB");}
}
