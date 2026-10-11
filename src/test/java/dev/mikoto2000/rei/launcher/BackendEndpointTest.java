package dev.mikoto2000.rei.launcher;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendEndpointTest {
  @TempDir Path root;
  private BackendEndpoint endpoint(String instance) {
    return new BackendEndpoint(1, instance, UUID.randomUUID().toString(),
        ProcessHandle.current().pid(), "http://127.0.0.1:8080", 1, "READY");
  }
  @Test void acceptsOnlyLiteralLoopbackEndpoints() {
    var value = endpoint(UUID.randomUUID().toString());
    assertEquals("127.0.0.1", value.uri().getHost());
    for (String url : new String[]{"http://example.com:8080", "http://127.0.0.1.evil:8080",
        "http://user:secret@127.0.0.1:8080", "http://127.0.0.1:8080/api", "http://127.0.0.1:8080?key=secret"}) {
      assertThrows(IllegalArgumentException.class, () -> new BackendEndpoint(1,value.instanceId(),
          value.storageId(),value.pid(),url,1,"READY"));
    }
  }
  @Test void requiresIdentityAndSupportedSchema() {
    var value = endpoint(UUID.randomUUID().toString());
    assertThrows(IllegalArgumentException.class, () -> new BackendEndpoint(2,value.instanceId(),
        value.storageId(),value.pid(),value.baseUrl(),1,"READY"));
    assertThrows(IllegalArgumentException.class, () -> new BackendEndpoint(1,"not-a-uuid",
        value.storageId(),value.pid(),value.baseUrl(),1,"READY"));
  }
  @Test void identityIsPersistentButInstanceChangesAndOldOwnerCannotDelete() throws Exception {
    var store = new BackendEndpointStore(root);
    String storageId = store.storageId();
    assertEquals(storageId, new BackendEndpointStore(root).storageId());
    var first = new BackendEndpoint(1,UUID.randomUUID().toString(),storageId,
        ProcessHandle.current().pid(),"http://127.0.0.1:8080",1,"READY");
    store.publish(first);
    assertEquals(first,store.read().orElseThrow());
    var second = new BackendEndpoint(1,UUID.randomUUID().toString(),storageId,
        ProcessHandle.current().pid(),"http://127.0.0.1:9090",1,"READY");
    store.publish(second);
    store.removeOwned(first.instanceId());
    assertEquals(second,store.read().orElseThrow());
    store.removeOwned(second.instanceId());
    assertTrue(store.read().isEmpty());
  }
  @Test void doesNotRepairCorruptIdentityOrAcceptOversizeEndpoint() throws Exception {
    var store = new BackendEndpointStore(root);
    Files.createDirectories(root.resolve(".storage"));
    Files.writeString(root.resolve(".storage/storage-id"),"corrupt");
    assertThrows(java.io.IOException.class,store::storageId);
    Files.writeString(root.resolve(".storage/backend-endpoint.json"),"x".repeat(16385));
    assertThrows(java.io.IOException.class,store::read);
  }
  @Test void lockFileExistenceDoesNotProveAnOwner() throws Exception {
    Files.createDirectories(root.resolve(".storage"));
    Path lock = root.resolve(".storage/instance.lock");
    Files.createFile(lock);
    assertFalse(BackendOwnership.isOwned(root));
    try (var channel = java.nio.channels.FileChannel.open(lock,StandardOpenOption.WRITE);
         var lease = channel.lock()) {
      assertTrue(BackendOwnership.isOwned(root));
    }
    assertFalse(BackendOwnership.isOwned(root));
  }
  private java.util.List<String> workerCommand() throws Exception {
    String javaExecutable = Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
    if (!Files.exists(Path.of(javaExecutable))) javaExecutable = Path.of(System.getProperty("java.home"),"bin","java").toString();
    Path arguments = root.resolve("worker.args");
    String classpath = System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
    Files.writeString(arguments,"-cp\n\""+classpath.replace("\\","\\\\").replace("\"","\\\"")+"\"\n"
        +BackendLockWorker.class.getName()+"\n\""+root.toString().replace("\\","\\\\")+"\"\n");
    return java.util.List.of(javaExecutable,"@"+arguments);
  }
  @Test void actualSecondJvmCannotOwnOrMigrateSameStorageAndCrashReleasesLock() throws Exception {
    var command = workerCommand();
    var first = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    try {
      var line = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try { return new java.io.BufferedReader(new java.io.InputStreamReader(first.getInputStream())).readLine(); }
        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
      });
      assertEquals("LOCKED",line.get(15,java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(BackendOwnership.isOwned(root));
      var second = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
      try {
        assertTrue(second.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(3,second.exitValue());
        assertFalse(Files.exists(root.resolve("storage.db")));
      } finally { if (second.isAlive()) second.destroyForcibly(); }
      first.destroyForcibly();
      assertTrue(first.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));
      assertFalse(BackendOwnership.isOwned(root));
      assertTrue(Files.exists(root.resolve(".storage/instance.lock")));
    } finally { if (first.isAlive()) first.destroyForcibly(); }
  }
  @Test void simultaneousJvmCandidatesLeaveExactlyOneOwner() throws Exception {
    var command = workerCommand();
    var candidates = new java.util.ArrayList<Process>();
    try {
      for (int i=0;i<2;i++) candidates.add(new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start());
      var lines = candidates.stream().map(process -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try { return new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream())).readLine(); }
        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
      })).toList();
      int owners = 0;
      for (int i=0;i<2;i++) {
        String line = lines.get(i).get(15,java.util.concurrent.TimeUnit.SECONDS);
        if ("LOCKED".equals(line)) owners++;
        else {
          assertNull(line);
          assertTrue(candidates.get(i).waitFor(15,java.util.concurrent.TimeUnit.SECONDS));
          assertEquals(3,candidates.get(i).exitValue());
        }
      }
      assertEquals(1,owners);
      assertTrue(BackendOwnership.isOwned(root));
      assertFalse(Files.exists(root.resolve("storage.db")));
    } finally {
      for (var candidate : candidates) {
        if (candidate.isAlive()) candidate.destroyForcibly();
        assertTrue(candidate.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));
      }
    }
    assertFalse(BackendOwnership.isOwned(root));
  }
}
