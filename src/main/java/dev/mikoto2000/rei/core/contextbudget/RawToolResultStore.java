package dev.mikoto2000.rei.core.contextbudget;

import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Full results live outside the prompt and event payload. References are conversation scoped. */
public class RawToolResultStore {
  public record Result(String toolName, String toolCallId, String rawResult) { }
  private final Path base;
  private final dev.mikoto2000.rei.storage.StorageObjectRegistry registry;
  private final ObjectMapper mapper = new ObjectMapper();
  public RawToolResultStore(Path base) { this(base,null); }
  public RawToolResultStore(Path base,dev.mikoto2000.rei.storage.StorageObjectRegistry registry) { this.base = base;this.registry=registry; }
  public String save(String conversation, String run, String toolName, String callId, String raw) {
    if(registry!=null)return registry.serialized(()->saveSerialized(conversation,run,toolName,callId,raw));
    return saveSerialized(conversation,run,toolName,callId,raw);
  }
  private String saveSerialized(String conversation, String run, String toolName, String callId, String raw) {
    String ref = ContextFiles.key(run + "\n" + callId + "\n" + toolName + "\n" + raw);
    Path file = file(conversation, ref);
    if(registry!=null)registry.verifyBodyPath(file);
    boolean created=!Files.exists(file);
    if (created) try { ContextFiles.write(file, mapper.writeValueAsString(new Result(toolName, callId, raw))); }
    catch (java.io.IOException e) { throw new IllegalStateException("Cannot encode tool result", e); }
    if(registry!=null)registry.registerRaw(conversation,run,ref,created);
    return ref;
  }
  public Result read(String conversation, String ref) {
    java.util.UUID.fromString(ref);
    if(registry!=null)registry.verifyRawReadable(conversation,ref);
    try(var input=Files.newInputStream(file(conversation,ref))) { return mapper.readValue(input, Result.class); }
    catch (java.io.IOException e) { throw new IllegalStateException("Cannot read raw tool result", e); }
  }
  public boolean exists(String conversation,String ref) {
    try{java.util.UUID.fromString(ref);if(registry!=null)registry.verifyRawReadable(conversation,ref);return Files.isRegularFile(file(conversation,ref));}
    catch(RuntimeException invalid){return false;}
  }
  private Path file(String conversation, String ref) {
    return ContextFiles.directory(base, conversation).resolve("results").resolve(ContextFiles.key(conversation)).resolve(ref + ".json");
  }
}
