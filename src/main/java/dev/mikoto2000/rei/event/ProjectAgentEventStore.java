package dev.mikoto2000.rei.event;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

/** Typed JSONL audit log. Project sequences are durable; the bus retains its process sequence API. */
@Component
public class ProjectAgentEventStore implements AgentEventListener {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProjectAgentEventStore.class);
  private final Path base;
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final Map<String, Long> sequences = new HashMap<>();
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "_payloadType")
  private interface PayloadType {}

  public ProjectAgentEventStore() { this(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()); }
  public ProjectAgentEventStore(Path base) {
    this.base = base;
    mapper.addMixIn(AgentEventPayload.class, PayloadType.class);
    for (Class<?> type : AgentEventPayload.class.getPermittedSubclasses())
      mapper.registerSubtypes(new NamedType(type, type.getSimpleName()));
  }
  @Override public void onEvent(AgentEvent event) { if (event.projectId() != null) append(event); }

  public synchronized AgentEvent append(AgentEvent event) {
    if (event.projectId() == null) throw new IllegalArgumentException("ProjectId is required for persisted agent events");
    long next = lastSequence(event.projectId()) + 1;
    var stored = new AgentEvent(event.id(), next, event.timestamp(), event.type(), event.version(), event.sessionId(),
        event.turnId(), event.runId(), event.correlationId(), event.parentEventId(), event.payload(), event.projectId());
    Path file = file(event.projectId());
    try {
      Files.createDirectories(file.getParent());
      String line = mapper.writeValueAsString(stored);
      // Separate an interrupted final line before appending a new complete record.
      String separator = "";
      if (Files.exists(file) && Files.size(file) > 0) try (var input = new RandomAccessFile(file.toFile(), "r")) {
        input.seek(input.length() - 1); if (input.read() != '\n') separator = "\n";
      }
      Files.writeString(file, separator + line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      sequences.put(event.projectId(), next);
      return stored;
    } catch (IOException error) { throw new IllegalStateException("Cannot persist agent event: " + file, error); }
  }
  public synchronized long lastSequence(String projectId) {
    return sequences.computeIfAbsent(projectId, id -> recent(id, 1).stream().mapToLong(AgentEvent::sequence).max().orElse(0));
  }

  /** Reads from the end of the file; project switching never scans or replays the complete log. */
  public synchronized List<AgentEvent> recent(String projectId, int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Event limit must be between 1 and 1000");
    Path file = file(projectId);
    if (!Files.exists(file)) return List.of();
    List<AgentEvent> result = new ArrayList<>();
    try (var input = new RandomAccessFile(file.toFile(), "r")) {
      var reversed = new ByteArrayOutputStream();
      long position = input.length();
      byte[] block = new byte[8192];
      while (position > 0 && result.size() < limit) {
        int length = (int) Math.min(block.length, position); position -= length;
        input.seek(position); input.readFully(block, 0, length);
        for (int i = length - 1; i >= 0 && result.size() < limit; i--) {
          if (block[i] == '\n') { addReversedLine(reversed, result, file); reversed.reset(); }
          else reversed.write(block[i]);
        }
      }
      if (result.size() < limit) addReversedLine(reversed, result, file);
    } catch (IOException error) { log.warn("Cannot restore recent agent events: {}", file, error); }
    Collections.reverse(result);
    return List.copyOf(result);
  }

  /** Forward pagination is separate from normal Shell restoration. */
  public List<AgentEvent> readAfter(String projectId, long afterSequence, int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Event limit must be between 1 and 1000");
    Path file = file(projectId);
    if (!Files.exists(file)) return List.of();
    List<AgentEvent> result = new ArrayList<>();
    try (var reader = Files.newBufferedReader(file)) {
      String line;
      while ((line = reader.readLine()) != null && result.size() < limit) {
        var event = decode(line, file);
        if (event != null && event.sequence() > afterSequence) result.add(event);
      }
    } catch (IOException error) { log.warn("Cannot replay agent events: {}", file, error); }
    return List.copyOf(result);
  }
  private void addReversedLine(ByteArrayOutputStream reversed, List<AgentEvent> result, Path file) {
    byte[] bytes = reversed.toByteArray();
    for (int i = 0, j = bytes.length - 1; i < j; i++, j--) { byte value = bytes[i]; bytes[i] = bytes[j]; bytes[j] = value; }
    var event = decode(new String(bytes, StandardCharsets.UTF_8), file);
    if (event != null) result.add(event);
  }
  private AgentEvent decode(String line, Path file) {
    if (line.isBlank()) return null;
    try { return mapper.readValue(line, AgentEvent.class); }
    catch (IOException error) { log.warn("Skipping malformed or unsupported agent event in {}: {}", file, error.getMessage()); return null; }
  }
  private Path file(String id) {
    UUID.fromString(id);
    return base.resolve("projects").resolve(id).resolve("events/events.jsonl");
  }
}
