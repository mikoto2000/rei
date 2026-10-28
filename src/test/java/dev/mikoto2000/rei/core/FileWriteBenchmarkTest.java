package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reproducible tool-payload and source/staging I/O measurements; no inferred LLM usage. */
@Tag("integration")
class FileWriteBenchmarkTest {
  @TempDir Path temporary;
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

  @Test
  void measureFixedWriteScenarios() throws Exception {
    var rows = new ArrayList<Map<String, Object>>();
    for (String scenario : List.of("single", "distant", "multiple", "large", "ambiguous",
        "conflict", "applyFailure", "rollback", "unknown", "crlfBom")) {
      Path root = Files.createDirectory(temporary.resolve(scenario)).toRealPath();
      StringBuilder source = new StringBuilder();
      int lines = scenario.equals("large") ? 9000 : 500;
      for (int i = 0; i < lines; i++) source.append("line ").append(i).append(" unchanged\n");
      String before = scenario.equals("ambiguous") ? "aaaa" : source.toString();
      if (scenario.equals("crlfBom")) before = "\ufeff" + before.replace("\n", "\r\n");
      String old = scenario.equals("ambiguous") ? "aaa" : "line 20 unchanged";
      var edits = new ArrayList<TextChangeSetService.Edit>();
      edits.add(new TextChangeSetService.Edit(old, "changed 20"));
      String after = before.replace(old, "changed 20");
      if (scenario.equals("distant") || scenario.equals("multiple")) {
        edits.add(new TextChangeSetService.Edit("line 480 unchanged", "changed 480"));
        after = after.replace("line 480 unchanged", "changed 480");
      }
      Files.writeString(root.resolve("a.txt"), before);
      Files.writeString(root.resolve("b.txt"), before);
      var repository = new TextChangeSetRepository(
          new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:" + root.resolve("db")));
      var service = new TextChangeSetService(repository);
      var counter = new AtomicInteger();
      var documents = new TextDocumentChangeSetService(repository, Clock.systemUTC(), (stage, target) -> {
        int count = counter.incrementAndGet();
        if (scenario.equals("unknown") && count == 2) {
          Files.writeString(root.resolve("a.txt"), "external");
          throw new java.io.IOException("injected second publication failure");
        }
        if (scenario.equals("rollback") && count == 2 || scenario.equals("applyFailure"))
          throw new java.io.IOException("injected publication failure");
        TextDocumentTransaction.replace(stage, target);
      });
      var owner = new AgentRunContext("benchmark", "session", root, UUID.randomUUID().toString());
      var project = new ProjectContext(owner.projectId(), "benchmark", root);
      boolean batch = List.of("multiple", "applyFailure", "rollback", "unknown").contains(scenario);
      Object request;
      if (batch) {
        if (Boolean.getBoolean("fileWrite.optimized")) {
          var op = new LinkedHashMap<String, Object>();
          op.put("kind", "UPDATE"); op.put("path", "a.txt");
          op.put("baseVersion", TextDocumentTransaction.hash(before)); op.put("edits", edits);
          var second = new LinkedHashMap<>(op); second.put("path", "b.txt");
          request = json.convertValue(Map.of("operations", List.of(op, second)), TextDocumentChangeSetService.Request.class);
        } else request = new TextDocumentChangeSetService.Request(List.of(
            new TextDocumentChangeSetService.Operation("UPDATE", "a.txt", null, before, after),
            new TextDocumentChangeSetService.Operation("UPDATE", "b.txt", null, before, after)));
      } else request = new TextChangeSetService.Request("a.txt", null, null,
          TextDocumentTransaction.hash(before), edits);
      var row = new LinkedHashMap<String, Object>();
      row.put("case", scenario);
      row.put("inputJsonBytes", json.writeValueAsBytes(request).length);
      long started = System.nanoTime();
      String status = "UNSUPPORTED";
      int calls = 1, output = 0, diff = 0;
      try (var recording = new Recording()) {
        recording.enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO);
        recording.enable("jdk.FileWrite").withThreshold(java.time.Duration.ZERO);
        recording.start();
        started = System.nanoTime();
        try {
          if (batch) {
            var proposal = documents.propose(owner, (TextDocumentChangeSetService.Request) request);
            output += json.writeValueAsBytes(proposal).length;
            diff += proposal.diff().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            calls++;
            var applied = documents.apply(owner, proposal.id(), proposal.proposalSha256());
            output += json.writeValueAsBytes(applied).length;
            status = applied.status();
          } else {
            var proposal = service.propose(project, (TextChangeSetService.Request) request);
            output += json.writeValueAsBytes(proposal).length;
            diff += proposal.diff().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (scenario.equals("conflict")) Files.writeString(root.resolve("a.txt"), "external");
            calls++;
            var applied = service.apply(project, proposal.id(), proposal.proposalSha256(), TextDocumentTransaction::writeSingle);
            output += json.writeValueAsBytes(applied).length;
            status = applied.status();
          }
        } catch (IllegalArgumentException rejected) {
          status = scenario.equals("large") ? "UNSUPPORTED" : "REJECTED";
          output += json.writeValueAsBytes(Map.of("error", rejected.getMessage())).length;
        }
        row.put("elapsedNanos", System.nanoTime() - started);
        recording.stop();
        Path trace = root.resolve("io.jfr"); recording.dump(trace);
        long reads = 0, writes = 0, readBytes = 0, writeBytes = 0;
        for (var event : RecordingFile.readAllEvents(trace)) {
          String type = event.getEventType().getName();
          if (!type.equals("jdk.FileRead") && !type.equals("jdk.FileWrite")) continue;
          String path = event.getString("path");
          if (path == null || !path.startsWith(root.toString())
              || !(path.endsWith(".txt") || path.endsWith(".tmp"))) continue;
          if (type.equals("jdk.FileRead")) { reads++; readBytes += Math.max(0, event.getLong("bytesRead")); }
          else { writes++; writeBytes += event.getLong("bytesWritten"); }
        }
        row.put("fileReadEvents", reads); row.put("fileWriteEvents", writes);
        row.put("readBytes", readBytes); row.put("writeBytes", writeBytes);
      }
      if (status.equals("APPLIED")) assertEquals(after, Files.readString(root.resolve("a.txt")));
      row.put("status", status); row.put("outputJsonBytes", output);
      row.put("diffBytes", diff); row.put("toolCalls", calls);
      rows.add(row);
    }
    Path output = Path.of(System.getProperty("fileWrite.output", "target/file-write-benchmark.json"));
    Files.createDirectories(output.toAbsolutePath().getParent());
    json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), rows);
  }
}
