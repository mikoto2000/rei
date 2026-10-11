package dev.mikoto2000.rei.launcher;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The Backend writes only while holding its storage lease; launchers only read. */
public final class BackendEndpointStore {
  private static final int MAX_BYTES = 16384;
  private final Path directory;
  private final ObjectMapper json = new ObjectMapper();
  public BackendEndpointStore(Path root) { directory = root.toAbsolutePath().normalize().resolve(".storage"); }
  private Path endpoint() { return directory.resolve("backend-endpoint.json"); }
  public Optional<BackendEndpoint> read() throws IOException {
    if (Files.notExists(endpoint(),LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
    return Optional.of(json.readValue(bounded(endpoint()),BackendEndpoint.class));
  }
  public String storageId() throws IOException {
    safeDirectory();
    Path file = directory.resolve("storage-id");
    if (Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) writeAtomic(file,UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String id = new String(bounded(file),java.nio.charset.StandardCharsets.UTF_8);
    try {
      if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException invalid) { throw new IOException("Invalid persistent storage identity",invalid); }
    return id;
  }
  /** Launcher identity check never creates or repairs persistent metadata. */
  public Optional<String> readStorageId() throws IOException {
    Path file=directory.resolve("storage-id");
    if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
    String id=new String(bounded(file),java.nio.charset.StandardCharsets.UTF_8);
    try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}
    catch(IllegalArgumentException invalid){throw new IOException("Invalid persistent storage identity");}
    return Optional.of(id);
  }
  public void publish(BackendEndpoint value) throws IOException {
    safeDirectory();
    if (!storageId().equals(value.storageId())) throw new IOException("Storage identity mismatch");
    writeAtomic(endpoint(),json.writeValueAsBytes(value));
  }
  public void removeOwned(String instanceId) throws IOException {
    var current = read();
    if (current.isPresent() && current.get().instanceId().equals(instanceId)) Files.delete(endpoint());
  }
  private byte[] bounded(Path file) throws IOException {
    safePath(file);
    try (var stream = Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = stream.readNBytes(MAX_BYTES+1);
      if (bytes.length > MAX_BYTES) throw new IOException("Backend metadata capacity exceeded");
      return bytes;
    }
  }
  private void safeDirectory() throws IOException {
    safePath(directory);
    Files.createDirectories(directory);
  }
  private static void safePath(Path path) throws IOException {
    for (Path part = path; part != null; part = part.getParent()) {
      if (Files.notExists(part,LinkOption.NOFOLLOW_LINKS)) continue;
      var attributes = Files.readAttributes(part,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Unsafe discovery metadata path");
    }
    if (Files.exists(path,LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsupported discovery path");
  }
  private void writeAtomic(Path destination,byte[] bytes) throws IOException {
    safePath(destination);
    Path temporary = Files.createTempFile(directory,".endpoint-",".tmp");
    try {
      restrict(temporary);
      try (var channel = java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)) {
        var buffer = java.nio.ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      // Do not silently fall back to a non-atomic replacement.
      Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    } finally { Files.deleteIfExists(temporary); }
  }
  private static void restrict(Path file) throws IOException {
    var acl = Files.getFileAttributeView(file,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
    if (acl != null) {
      var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
          .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
      acl.setAcl(List.of(entry));
    } else {
      var posix = Files.getFileAttributeView(file,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
      if (posix == null) throw new IOException("Filesystem cannot protect backend metadata");
      posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
    }
  }
}
