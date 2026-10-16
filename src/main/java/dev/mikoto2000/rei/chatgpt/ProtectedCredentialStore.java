package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.AUTH_INVALID;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.CANCELLED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.STORE_UNAVAILABLE;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** JVM lock + OS file lock cover the entire refresh/read/atomic replace transaction. */
public final class ProtectedCredentialStore {
  private static final Map<Path, ReentrantLock> LOCKS =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final Path directory;
  private final SecretProtector protector;
  private final ObjectMapper json = new ObjectMapper();

  public ProtectedCredentialStore(Path directory, SecretProtector protector) {
    this.directory = directory.toAbsolutePath().normalize();
    this.protector = protector;
  }

  public <T> T locked(Function<Transaction, T> action) {
    var mutex = LOCKS.computeIfAbsent(directory, p -> new ReentrantLock());
    try {
      if (!mutex.tryLock(20, java.util.concurrent.TimeUnit.SECONDS))
        throw ChatGptException.of(STORE_UNAVAILABLE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ChatGptException.of(CANCELLED);
    }
    try {
      Files.createDirectories(directory);
      if (Files.isSymbolicLink(directory)) throw ChatGptException.of(STORE_UNAVAILABLE);
      if (Files.isSymbolicLink(directory.resolve("credentials.lock"))
          || Files.isSymbolicLink(directory.resolve("host-id")))
        throw ChatGptException.of(STORE_UNAVAILABLE);
      try (var channel =
              FileChannel.open(
                  directory.resolve("credentials.lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE);
          var lock = acquire(channel)) {
        return action.apply(new Transaction());
      }
    } catch (ChatGptException error) {
      throw error;
    } catch (Exception error) {
      throw ChatGptException.of(STORE_UNAVAILABLE);
    } finally {
      mutex.unlock();
    }
  }

  public final class Transaction {
    public TokenSet load(String account) {
      validate(account);
      try {
        var all = read();
        var node = all.get(account);
        return node == null ? null : json.treeToValue(node, TokenSet.class);
      } catch (Exception e) {
        throw ChatGptException.of(STORE_UNAVAILABLE);
      }
    }

    public Set<String> accounts() {
      try {
        var all = read();
        var result = new TreeSet<String>();
        all.fieldNames().forEachRemaining(result::add);
        return result;
      } catch (Exception e) {
        throw ChatGptException.of(STORE_UNAVAILABLE);
      }
    }

    public void save(String account, TokenSet value) {
      validate(account);
      byte[] plain = null, encrypted = null;
      Path temporary = null;
      try {
        var all = read();
        all.set(account, json.valueToTree(value));
        plain = json.writeValueAsBytes(all);
        encrypted = protector.protect(plain);
        temporary = Files.createTempFile(directory, "credentials-", ".tmp");
        try (var out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
          var buffer = java.nio.ByteBuffer.wrap(encrypted);
          while (buffer.hasRemaining()) out.write(buffer);
          out.force(true);
        }
        Files.move(
            temporary,
            directory.resolve("credentials.bin"),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (Exception e) {
        throw ChatGptException.of(STORE_UNAVAILABLE);
      } finally {
        if (plain != null) Arrays.fill(plain, (byte) 0);
        if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        if (temporary != null)
          try {
            Files.deleteIfExists(temporary);
          } catch (Exception ignored) {
          }
      }
    }

    public String hostId() {
      try {
        var file = directory.resolve("host-id");
        if (!Files.exists(file))
          Files.writeString(file, "urn:uuid:" + UUID.randomUUID(), StandardOpenOption.CREATE_NEW);
        var value = Files.readString(file);
        UUID.fromString(value.substring("urn:uuid:".length()));
        if (!value.startsWith("urn:uuid:")) throw new IllegalStateException();
        return value;
      } catch (Exception e) {
        throw ChatGptException.of(STORE_UNAVAILABLE);
      }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode read() throws Exception {
      var file = directory.resolve("credentials.bin");
      if (!Files.exists(file)) return json.createObjectNode();
      if (Files.isSymbolicLink(file) || Files.size(file) > 1048576)
        throw new IllegalStateException();
      byte[] plain = protector.unprotect(Files.readAllBytes(file));
      try {
        var parsed = json.readTree(plain);
        if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode all))
          throw new IllegalStateException();
        return all;
      } finally {
        Arrays.fill(plain, (byte) 0);
      }
    }
  }

  private static java.nio.channels.FileLock acquire(FileChannel channel) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      var lock = channel.tryLock();
      if (lock != null) return lock;
      Thread.sleep(25);
    }
    throw ChatGptException.of(STORE_UNAVAILABLE);
  }

  private static void validate(String account) {
    if (account == null || !account.matches("[a-zA-Z0-9_-]{1,64}"))
      throw ChatGptException.of(AUTH_INVALID);
  }
}
