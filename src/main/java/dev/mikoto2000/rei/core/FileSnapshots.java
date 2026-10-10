package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Request-local, bounded immutable bytes. No metadata-only cross-request cache. */
final class FileSnapshots {
  static final int MAX_FILE_BYTES = 1024 * 1024;
  static final int MAX_REQUEST_BYTES = 8 * MAX_FILE_BYTES;
  static final int MAX_FILES = 1024;
  @FunctionalInterface interface Reader { byte[] read(Path path) throws IOException; }
  record Snapshot(Path path, String version, byte[] bytes) {
    Snapshot { bytes=bytes.clone(); }
    @Override public byte[] bytes(){return bytes.clone();}
  }
  private final Map<Path,Snapshot> files = new LinkedHashMap<>();
  private final Reader reader;
  private int bytes;
  private final Map<List<String>,List<String>> inventories=new HashMap<>();
  @FunctionalInterface interface Inventory {List<String> list() throws IOException,InterruptedException;}
  synchronized List<String> inventory(String base,Inventory loader)throws IOException,InterruptedException {
    return inventory(List.of(base),loader);
  }
  synchronized List<String> inventory(List<String> key,Inventory loader)throws IOException,InterruptedException {
    key=List.copyOf(key);
    var cached=inventories.get(key);if(cached!=null)return cached;
    var paths=loader.list().stream().distinct().sorted().toList();
    if(paths.size()>MAX_FILES)throw new IOException("File inventory limit reached (1024); narrow baseDir/globs");
    if(paths.stream().anyMatch(path->path.length()>1024))throw new IOException("Inventory path limit reached (1024 characters)");
    inventories.put(key,paths);return paths;
  }
  FileSnapshots(){this(path->{try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){return in.readNBytes(MAX_FILE_BYTES+1);}});}
  FileSnapshots(Reader reader){this.reader=reader;}
  synchronized Snapshot get(Path root, Path input) throws IOException {
    RunCancellation.propagate(null);
    Path path=resolve(root,input);
    var existing=files.get(path);if(existing!=null)return existing;
    if(files.size()>=MAX_FILES)throw new IOException("Snapshot file limit reached");
    var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
    if(before.size()>MAX_FILE_BYTES || before.size()>MAX_REQUEST_BYTES-bytes)throw new IOException("Snapshot byte limit reached");
    var content=reader.read(path);
    var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
    RunCancellation.propagate(null);
    if(!before.lastModifiedTime().equals(after.lastModifiedTime()) || !before.creationTime().equals(after.creationTime())
        || before.size()!=after.size() || !Objects.equals(before.fileKey(),after.fileKey()) || after.size()!=content.length)
      throw new IOException("File changed during snapshot read; retry the request");
    if(content.length>MAX_FILE_BYTES || content.length>MAX_REQUEST_BYTES-bytes)throw new IOException("Snapshot byte limit reached");
    var snapshot=new Snapshot(path,hash(content),content);files.put(path,snapshot);bytes+=content.length;return snapshot;
  }
  static Path resolve(Path directory,Path input)throws IOException {
    var path=resolveInventoryPath(directory,input);
    if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new NoSuchFileException(path.toString());
    return path;
  }
  static Path resolveInventoryPath(Path directory,Path input)throws IOException {
    if(input.toString().length()>1024)throw new IOException("Path exceeds 1024 characters");
    var root=directory.toRealPath();var path=(input.isAbsolute()?input:root.resolve(input)).normalize();
    if(!path.startsWith(root) || RepositoryMapService.sensitive(root.relativize(path)))throw new IOException("Excluded or outside Project path");
    var cursor=root;for(var part:root.relativize(path)){cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor) || Files.exists(cursor,LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().equals(cursor))throw new IOException("Linked or aliased paths are unsupported");}
    if(Files.exists(path,LinkOption.NOFOLLOW_LINKS) && !path.toRealPath().startsWith(root))throw new IOException("Outside Project path");return path;
  }
  static String hash(byte[] content){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
}
