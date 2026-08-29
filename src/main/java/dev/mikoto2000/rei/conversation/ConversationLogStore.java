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
    this(ReiPaths.conversationLogsDirectory(), Clock.systemDefaultZone(),
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
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        try {
          entries.add(objectMapper.readValue(line, ConversationLogEntry.class));
        } catch (IOException e) {
          log.warn("Skipping malformed conversation log line in {}", file);
        }
      }
    } catch (IOException e) {
      log.warn("Failed to read conversation log file: {}", file, e);
    }
  }

  static String scopeOf(String conversationId) {
    if (conversationId.startsWith("bluesky-reply:")) return "bluesky-reply";
    if (conversationId.startsWith("bluesky-manual:")) return "bluesky-manual";
    if (conversationId.startsWith("tool:")) return "tool";
    return "chat";
  }

  private String normalizeSpeaker(String speaker) {
    return speaker == null ? "" : speaker.toLowerCase(Locale.ROOT);
  }
}
