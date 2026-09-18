package dev.mikoto2000.rei.core.contextbudget;

import java.nio.file.*;
import java.util.concurrent.locks.ReentrantLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Atomic project-local snapshots. Bounded striped locks serialize read/generate/commit across runs. */
public class ConversationSummaryRepository {
  private final Path base;
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final ReentrantLock[] locks = java.util.stream.IntStream.range(0, 64)
      .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);
  public ConversationSummaryRepository(Path base) { this.base = base; }
  public ReentrantLock lock(String id) { return locks[Math.floorMod(id.hashCode(), locks.length)]; }
  public ConversationSummary read(String id) {
    Path file = file(id);
    if (!Files.exists(file)) return ConversationSummary.empty();
    try { return mapper.readValue(Files.readString(file), ConversationSummary.class); }
    catch (java.io.IOException e) { throw new IllegalStateException("Cannot read context summary", e); }
  }
  public void save(String id, ConversationSummary summary) {
    if (summary.throughSequence() <= read(id).throughSequence()) return;
    try { ContextFiles.write(file(id), mapper.writeValueAsString(summary)); }
    catch (java.io.IOException e) { throw new IllegalStateException("Cannot encode context summary", e); }
  }
  private Path file(String id) { return ContextFiles.directory(base, id).resolve("summaries/" + ContextFiles.key(id) + ".json"); }
  public static String runKey(String conversation, String run) { return conversation + "/run/" + run; }
}
