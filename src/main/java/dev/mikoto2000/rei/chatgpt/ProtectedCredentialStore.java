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

/**
 * 保護された認証情報の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>JVM lock + OS file lock cover the entire refresh/read/atomic replace transaction.
 */
public final class ProtectedCredentialStore {
  /** 排他制御一覧。 */
  private static final Map<Path, ReentrantLock> LOCKS =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** ディレクトリ。 */
  private final Path directory;

  /** 秘密情報を保護する窓口。 */
  private final SecretProtector protector;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper();

  /** 指定された値や利用先を使って、ProtectedCredentialStoreを初期化する。 */
  public ProtectedCredentialStore(Path directory, SecretProtector protector) {
    this.directory = directory.toAbsolutePath().normalize();
    this.protector = protector;
  }

  /** ChatGPTとの連携で用いる使用中を返す。 */
  public <T> T locked(Function<Transaction, T> action) {
    var mutex = LOCKS.computeIfAbsent(directory, p -> new ReentrantLock());
    try {
      if (!mutex.tryLock(20, java.util.concurrent.TimeUnit.SECONDS))
        throw ChatGptException.of(STORE_UNAVAILABLE);
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
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

  /** ChatGPTとの連携に必要な一連の更新を扱う。 */
  public final class Transaction {
    /** 保存された情報を読み込み、現在の処理で使える形にする。 */
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

    /** ChatGPTとの連携で用いるアカウント一覧を返す。 */
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

    /** 後から参照できるよう、指定された情報を保存する。 */
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

    /** ChatGPTとの連携で用いるホスト識別子を返す。 */
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

    /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
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

  /** ChatGPTとの連携で用いる確保を返す。 */
  private static java.nio.channels.FileLock acquire(FileChannel channel) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      var lock = channel.tryLock();
      if (lock != null) return lock;
      Thread.sleep(25);
    }
    throw ChatGptException.of(STORE_UNAVAILABLE);
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private static void validate(String account) {
    if (account == null || !account.matches("[a-zA-Z0-9_-]{1,64}"))
      throw ChatGptException.of(AUTH_INVALID);
  }
}
