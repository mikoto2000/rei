package dev.mikoto2000.rei.core.contextbudget;

import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Full results live outside the prompt and event payload. References are conversation scoped. */
public class RawToolResultStore {
  public record Result(String toolName, String toolCallId, String rawResult) { }
  private final Path base;
  private final ObjectMapper mapper = new ObjectMapper();
  public RawToolResultStore(Path base) { this.base = base; }
  public String save(String conversation, String run, String toolName, String callId, String raw) {
    String ref = ContextFiles.key(run + "\n" + callId + "\n" + toolName + "\n" + raw);
    Path file = file(conversation, ref);
    if (!Files.exists(file)) try { ContextFiles.write(file, mapper.writeValueAsString(new Result(toolName, callId, raw))); }
    catch (java.io.IOException e) { throw new IllegalStateException("Cannot encode tool result", e); }
    return ref;
  }
  public Result read(String conversation, String ref) {
    java.util.UUID.fromString(ref);
    try { return mapper.readValue(Files.readString(file(conversation, ref)), Result.class); }
    catch (java.io.IOException e) { throw new IllegalStateException("Cannot read raw tool result", e); }
  }
  private Path file(String conversation, String ref) {
    return ContextFiles.directory(base, conversation).resolve("results").resolve(ContextFiles.key(conversation)).resolve(ref + ".json");
  }
}
