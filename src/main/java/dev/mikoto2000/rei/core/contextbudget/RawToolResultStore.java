package dev.mikoto2000.rei.core.contextbudget;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable content-addressed bodies with call-specific pointers and conversation-scoped access.
 */
public class RawToolResultStore {
  public record Result(String toolName, String toolCallId, String rawResult) {}

  private record Stored(String toolName, String toolCallId, String rawResult, String bodyRef) {}

  private record CacheKey(AgentRunContext owner, String ref) {}

  private record Cached(Result result, long bytes, long loaded, String bodyRef) {}

  private final Path base;
  private final dev.mikoto2000.rei.storage.StorageObjectRegistry registry;
  private final ObjectMapper mapper = new ObjectMapper();
  private final long maxResultBytes, maxStoreBytes, maxCacheBytes;
  private final LinkedHashMap<CacheKey, Cached> cache = new LinkedHashMap<>(16, .75f, true);
  private long cachedBytes;
  private final AtomicLong bodyReads = new AtomicLong(), bodyWrites = new AtomicLong();

  public RawToolResultStore(Path base) {
    this(base, null);
  }

  public RawToolResultStore(Path base, dev.mikoto2000.rei.storage.StorageObjectRegistry registry) {
    this(base, registry, 16L * 1024 * 1024, 256L * 1024 * 1024, 8L * 1024 * 1024);
  }

  public RawToolResultStore(
      Path base,
      dev.mikoto2000.rei.storage.StorageObjectRegistry registry,
      long maxResultBytes,
      long maxStoreBytes,
      long maxCacheBytes) {
    if (maxResultBytes < 1 || maxStoreBytes < 1 || maxCacheBytes < 0)
      throw new IllegalArgumentException("Invalid raw result capacity");
    this.base = base;
    this.registry = registry;
    this.maxResultBytes = maxResultBytes;
    this.maxStoreBytes = maxStoreBytes;
    this.maxCacheBytes = maxCacheBytes;
  }

  public long bodyReadCount() {
    return bodyReads.get();
  }

  public long bodyWriteCount() {
    return bodyWrites.get();
  }

  public synchronized void clearRun(String run) {
    var entries = cache.entrySet().iterator();
    while (entries.hasNext()) {
      var entry = entries.next();
      if (entry.getKey().owner().runId().equals(run)) {
        cachedBytes -= entry.getValue().bytes();
        entries.remove();
      }
    }
  }

  public String save(String conversation, String run, String toolName, String callId, String raw) {
    if (registry != null)
      return registry.serialized(() -> saveSerialized(conversation, run, toolName, callId, raw));
    synchronized (this) {
      return saveSerialized(conversation, run, toolName, callId, raw);
    }
  }

  private String saveSerialized(
      String conversation, String run, String toolName, String callId, String raw) {
    try {
      byte[] body = mapper.writeValueAsBytes(raw);
      if (body.length > maxResultBytes)
        throw new IllegalStateException("RAW_RESULT_CAPACITY_EXCEEDED");
      String ref = ContextFiles.key(run + "\n" + callId + "\n" + toolName + "\n" + raw);
      Path pointer = file(conversation, ref);
      verify(pointer);
      if (Files.exists(pointer)) {
        var existing = read(conversation, ref);
        if (!existing.toolName().equals(toolName)
            || !existing.toolCallId().equals(callId)
            || !existing.rawResult().equals(raw))
          throw new IllegalStateException("RAW_RESULT_IDENTITY_CHANGED");
        return ref;
      }
      String hash = hash(body);
      Path content = bodyFile(conversation, hash);
      verify(content);
      byte[] metadata = mapper.writeValueAsBytes(new Stored(toolName, callId, null, hash));
      if (metadata.length > 128 * 1024)
        throw new IllegalStateException("RAW_RESULT_CAPACITY_EXCEEDED");
      boolean created = !Files.exists(content);
      long additional = metadata.length + (created ? body.length : 0);
      long used = registry == null ? localBytes(conversation) : registry.rawStoredBytes();
      if (additional > maxStoreBytes - used)
        throw new IllegalStateException("RAW_RESULT_CAPACITY_EXCEEDED");
      try {
        if (created) {
          ContextFiles.write(content, new String(body, java.nio.charset.StandardCharsets.UTF_8));
          bodyWrites.incrementAndGet();
        } else if (!hash(Files.readAllBytes(content)).equals(hash))
          throw new IllegalStateException("RAW_RESULT_IDENTITY_CHANGED");
        ContextFiles.write(pointer, new String(metadata, java.nio.charset.StandardCharsets.UTF_8));
        if (registry != null)
          registry.registerRawWithBody(conversation, run, ref, hash, true, created);
        return ref;
      } catch (RuntimeException failure) {
        try {
          // Only files created by this serialized save, and only after proving the registration did
          // not commit.
          if (registry == null || registry.rawObject(conversation, ref).isEmpty()) {
            Files.deleteIfExists(pointer);
            if (created) Files.deleteIfExists(content);
          }
        } catch (Exception cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot encode tool result", error);
    }
  }

  public Result read(String conversation, String ref) {
    var actor = AgentRunScope.current();
    if (actor != null && !actor.conversationId().equals(conversation))
      throw new IllegalStateException("RAW_RESULT_ACCESS_DENIED");
    UUID.fromString(ref);
    Path pointer = file(conversation, ref);
    verify(pointer);
    if (registry != null) registry.verifyRawReadable(conversation, ref);
    if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException("RAW_RESULT_NOT_FOUND");
    var owner = AgentRunScope.current();
    CacheKey key =
        owner != null && owner.conversationId().equals(conversation)
            ? new CacheKey(owner, ref)
            : null;
    synchronized (this) {
      var hit = key == null ? null : cache.get(key);
      if (hit != null && System.nanoTime() - hit.loaded() < 600_000_000_000L) {
        if (hit.bodyRef() != null) {
          var path = bodyFile(conversation, hit.bodyRef());
          verify(path);
          if (registry != null) registry.verifyRawBodyReadable(conversation, hit.bodyRef());
          if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("RAW_RESULT_NOT_FOUND");
        }
        return hit.result();
      }
      if (hit != null) {
        cachedBytes -= hit.bytes();
        cache.remove(key);
      }
    }
    try {
      if (Files.size(pointer) > maxResultBytes + 128 * 1024)
        throw new IllegalStateException("RAW_RESULT_CAPACITY_EXCEEDED");
      Stored stored;
      try (var input = Files.newInputStream(pointer, LinkOption.NOFOLLOW_LINKS)) {
        stored = mapper.readValue(input, Stored.class);
      }
      String raw = stored.rawResult();
      if (stored.bodyRef() != null) {
        if (registry != null && registry.rawObject(conversation, ref).isEmpty())
          throw new IllegalStateException("RAW_RESULT_NOT_FOUND: uncommitted pointer");
        if (raw != null) throw new IllegalStateException("RAW_RESULT_IDENTITY_CHANGED");
        Path body = bodyFile(conversation, stored.bodyRef());
        verify(body);
        if (registry != null) registry.verifyRawBodyReadable(conversation, stored.bodyRef());
        if (!Files.isRegularFile(body, LinkOption.NOFOLLOW_LINKS))
          throw new IllegalStateException("RAW_RESULT_NOT_FOUND");
        if (Files.size(body) > maxResultBytes)
          throw new IllegalStateException("RAW_RESULT_CAPACITY_EXCEEDED");
        byte[] bytes = Files.readAllBytes(body);
        bodyReads.incrementAndGet();
        if (!hash(bytes).equals(stored.bodyRef()))
          throw new IllegalStateException("RAW_RESULT_IDENTITY_CHANGED");
        raw = mapper.readValue(bytes, String.class);
      } else bodyReads.incrementAndGet(); // Legacy embedded body.
      if (raw == null || stored.toolName() == null || stored.toolCallId() == null)
        throw new IllegalStateException("RAW_RESULT_IDENTITY_CHANGED");
      var result = new Result(stored.toolName(), stored.toolCallId(), raw);
      long bytes =
          128L + 2L * (raw.length() + stored.toolName().length() + stored.toolCallId().length());
      synchronized (this) {
        if (key != null && bytes <= maxCacheBytes) {
          var old = cache.remove(key);
          if (old != null) cachedBytes -= old.bytes();
          while (!cache.isEmpty() && (cachedBytes + bytes > maxCacheBytes || cache.size() >= 128)) {
            var iterator = cache.entrySet().iterator();
            var removed = iterator.next();
            cachedBytes -= removed.getValue().bytes();
            iterator.remove();
          }
          cache.put(key, new Cached(result, bytes, System.nanoTime(), stored.bodyRef()));
          cachedBytes += bytes;
        }
      }
      return result;
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot read raw tool result", error);
    }
  }

  public boolean exists(String conversation, String ref) {
    try {
      read(conversation, ref);
      return true;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private void verify(Path path) {
    if (registry != null) registry.verifyBodyPath(path);
  }

  private long localBytes(String conversation) throws java.io.IOException {
    long size = 0;
    for (String folder : List.of("results", "bodies")) {
      Path directory =
          ContextFiles.directory(base, conversation)
              .resolve(folder)
              .resolve(ContextFiles.key(conversation));
      if (!Files.exists(directory)) continue;
      try (var files = Files.list(directory)) {
        for (var path : files.toList())
          if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            size = Math.addExact(size, Files.size(path));
      }
    }
    return size;
  }

  private Path file(String conversation, String ref) {
    return ContextFiles.directory(base, conversation)
        .resolve("results")
        .resolve(ContextFiles.key(conversation))
        .resolve(ref + ".json");
  }

  private Path bodyFile(String conversation, String ref) {
    if (ref == null || !ref.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("INVALID_CURSOR");
    return ContextFiles.directory(base, conversation)
        .resolve("bodies")
        .resolve(ContextFiles.key(conversation))
        .resolve(ref + ".json");
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
