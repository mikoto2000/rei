package dev.mikoto2000.rei.conversation;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.mikoto2000.rei.core.datasource.ReiPaths;

@Component
public class ConversationLogStore {

  private static final Logger log = LoggerFactory.getLogger(ConversationLogStore.class);
  private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  private final Path directory;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final Object writeLock = new Object();

  public ConversationLogStore() {
    this(null, Clock.systemDefaultZone(),
        new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  ConversationLogStore(Path directory, Clock clock, ObjectMapper objectMapper) {
    this.directory = directory;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  public void append(String conversationId, String speaker, String content) {
    if (conversationId == null || conversationId.isBlank() || content == null || content.isBlank()) {
      return;
    }
    OffsetDateTime timestamp = OffsetDateTime.now(clock);
    ConversationLogEntry entry = new ConversationLogEntry(
        conversationId.strip(), scopeOf(conversationId), normalizeSpeaker(speaker), timestamp, content);
    Path directory = directoryFor(conversationId);
    Path file = directory.resolve(FILE_DATE.format(timestamp) + ".jsonl");
    synchronized (writeLock) {
      try {
        Files.createDirectories(directory);
        Files.writeString(file, objectMapper.writeValueAsString(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException e) {
        log.warn("Failed to append conversation log: {}", file, e);
      }
    }
  }

  public List<ConversationLogEntry> readAll() {
    return readDirectory(directoryFor(null));
  }

  /** Read a project's authoritative log without changing the selected project or run scope. */
  public List<ConversationLogEntry> readProject(String projectId) {
    var entries = new ArrayList<ConversationLogEntry>();
    visitProject(projectId, entries::add);
    entries.sort(Comparator.comparing(ConversationLogEntry::timestamp));
    return entries;
  }

  /** Streams one project without retaining message bodies or changing Shell/Agent scope. */
  public void visitProject(String projectId, java.util.function.Consumer<ConversationLogEntry> visitor) {
    Path location = dev.mikoto2000.rei.core.project.ProjectStorage.directory(projectId).resolve("conversations");
    if (!Files.isDirectory(location)) return;
    try (var files = Files.list(location)) {
      for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
        readFile(file, entry -> {
          if (entry.conversationId() != null && entry.conversationId().startsWith("project:" + projectId + ":")
              && entry.timestamp() != null) visitor.accept(entry);
        });
      }
    } catch (IOException error) { throw new IllegalStateException("Cannot read conversation history", error); }
  }

  public record ConversationSummary(String conversationId, OffsetDateTime updatedAt, long messageCount) {}

  public List<ConversationSummary> listConversations(String projectId, int limit, int offset) {
    if (limit < 1 || offset < 0) throw new IllegalArgumentException("Invalid history page");
    var summaries = new java.util.HashMap<String, ConversationSummary>();
    visitProject(projectId, entry -> summaries.compute(entry.conversationId(), (id, previous) ->
        new ConversationSummary(id, previous == null || entry.timestamp().isAfter(previous.updatedAt())
            ? entry.timestamp() : previous.updatedAt(), previous == null ? 1 : previous.messageCount() + 1)));
    return summaries.values().stream().sorted(Comparator.comparing(ConversationSummary::updatedAt).reversed()
        .thenComparing(ConversationSummary::conversationId)).skip(offset).limit(limit).toList();
  }

  public List<ConversationLogEntry> recentConversation(String projectId, String conversationId, int limit) {
    if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    record Ordered(ConversationLogEntry entry, long sequence) {}
    var order = Comparator.comparing((Ordered e) -> e.entry().timestamp()).thenComparingLong(Ordered::sequence);
    var recent = new java.util.PriorityQueue<Ordered>(order);
    long[] sequence = {0};
    visitProject(projectId, entry -> {
      if (entry.conversationId().equals(conversationId)) {
        recent.add(new Ordered(entry, sequence[0]++));
        if (recent.size() > limit) recent.remove();
      }
    });
    return recent.stream().sorted(order).map(Ordered::entry).toList();
  }

  private List<ConversationLogEntry> readDirectory(Path directory) {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<ConversationLogEntry> entries = new ArrayList<>();
    try (var files = Files.list(directory)) {
      for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
        readFile(file, entries);
      }
    } catch (IOException e) {
      log.warn("Failed to read conversation log directory: {}", directory, e);
    }
    entries.sort(Comparator.comparing(ConversationLogEntry::timestamp));
    return entries;
  }

  private void readFile(Path file, List<ConversationLogEntry> entries) {
    readFile(file, entries::add);
  }

  private void readFile(Path file, java.util.function.Consumer<ConversationLogEntry> visitor) {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        try {
          visitor.accept(objectMapper.readValue(line, ConversationLogEntry.class));
        } catch (IOException e) {
          log.warn("Skipping malformed conversation log line in {}", file);
        }
      }
    } catch (IOException e) {
      log.warn("Failed to read conversation log file: {}", file, e);
    }
  }

  static String scopeOf(String conversationId) {
    if (conversationId.startsWith("project:")) conversationId = conversationId.substring(conversationId.indexOf(':', 8) + 1);
    if (conversationId.startsWith("bluesky-reply:")) return "bluesky-reply";
    if (conversationId.startsWith("bluesky-manual:")) return "bluesky-manual";
    if (conversationId.startsWith("tool:")) return "tool";
    return "chat";
  }

  private String normalizeSpeaker(String speaker) {
    return speaker == null ? "" : speaker.toLowerCase(Locale.ROOT);
  }
  private Path directoryFor(String conversationId) {
    if (directory != null) return directory;
    String id = dev.mikoto2000.rei.core.project.ProjectStorage.projectId(conversationId);
    return (id == null ? dev.mikoto2000.rei.core.project.ProjectStorage.currentDirectory()
        : dev.mikoto2000.rei.core.project.ProjectStorage.directory(id)).resolve("conversations");
  }
}
