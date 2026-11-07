package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
    for (String scenario :
        List.of(
            "single",
            "distant",
            "multiple",
            "large",
            "ambiguous",
            "conflict",
            "applyFailure",
            "rollback",
            "unknown",
            "crlfBom",
            "cp932")) {
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
      if (scenario.equals("cp932")) {
        before = "日本語\r\nold\r\n";
        old = "old";
        after = "日本語\r\nchanged 20\r\n";
      }
      Files.writeString(
          root.resolve("a.txt"),
          before,
          scenario.equals("cp932")
              ? java.nio.charset.Charset.forName("windows-31j")
              : java.nio.charset.StandardCharsets.UTF_8);
      Files.writeString(root.resolve("b.txt"), before);
      var repository =
          new TextChangeSetRepository(
              new org.springframework.jdbc.datasource.DriverManagerDataSource(
                  "jdbc:sqlite:" + root.resolve("db")));
      var service = new TextChangeSetService(repository);
      var counter = new AtomicInteger();
      var documents =
          new TextDocumentChangeSetService(
              repository,
              Clock.systemUTC(),
              (stage, target) -> {
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
        if (Boolean.parseBoolean(System.getProperty("fileWrite.optimized", "true"))) {
          var op = new LinkedHashMap<String, Object>();
          op.put("kind", "UPDATE");
          op.put("path", "a.txt");
          op.put("baseVersion", TextDocumentTransaction.hash(before));
          op.put("edits", edits);
          var second = new LinkedHashMap<>(op);
          second.put("path", "b.txt");
          request =
              json.convertValue(
                  Map.of("operations", List.of(op, second)),
                  TextDocumentChangeSetService.Request.class);
        } else
          request =
              new TextDocumentChangeSetService.Request(
                  List.of(
                      new TextDocumentChangeSetService.Operation(
                          "UPDATE", "a.txt", null, before, after),
                      new TextDocumentChangeSetService.Operation(
                          "UPDATE", "b.txt", null, before, after)));
      } else if (scenario.equals("cp932")) {
        request =
            Map.of(
                "pathStr",
                "a.txt",
                "oldText",
                "old",
                "newText",
                "changed 20",
                "charset",
                "windows-31j");
      } else
        request =
            new TextChangeSetService.Request(
                "a.txt", null, null, TextDocumentTransaction.hash(before), edits);
      var row = new LinkedHashMap<String, Object>();
      row.put("case", scenario);
      row.put("inputJsonBytes", json.writeValueAsBytes(request).length);
      long started = System.nanoTime();
      String status = "UNSUPPORTED";
      int calls = 1, output = 0, diff = 0;
      try (var recording = new Recording()) {
        recording.enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO).withStackTrace();
        recording.enable("jdk.FileWrite").withThreshold(java.time.Duration.ZERO);
        recording.start();
        started = System.nanoTime();
        try {
          if (scenario.equals("cp932")) {
            var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner);
            try (scope) {
              var result = new Tools().applyTextDiff("a.txt", "old", "changed 20", "windows-31j");
              output += json.writeValueAsBytes(result).length;
              status = result.success() ? "APPLIED" : "REJECTED";
            }
          } else if (batch) {
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
            var applied =
                service.apply(
                    project,
                    proposal.id(),
                    proposal.proposalSha256(),
                    TextDocumentTransaction::writeSingle);
            output += json.writeValueAsBytes(applied).length;
            status = applied.status();
          }
        } catch (IllegalArgumentException rejected) {
          status = scenario.equals("large") ? "UNSUPPORTED" : "REJECTED";
          output += json.writeValueAsBytes(Map.of("error", rejected.getMessage())).length;
        }
        row.put("elapsedNanos", System.nanoTime() - started);
        recording.stop();
        Path trace = root.resolve("io.jfr");
        recording.dump(trace);
        long reads = 0, writes = 0, readBytes = 0, writeBytes = 0;
        var applyReads = new LinkedHashMap<String, List<Long>>();
        for (var event : RecordingFile.readAllEvents(trace)) {
          String type = event.getEventType().getName();
          if (!type.equals("jdk.FileRead") && !type.equals("jdk.FileWrite")) continue;
          String path = event.getString("path");
          if (path == null
              || !path.startsWith(root.toString())
              || !(path.endsWith(".txt") || path.endsWith(".tmp"))) continue;
          if (type.equals("jdk.FileRead")) {
            reads++;
            readBytes += Math.max(0, event.getLong("bytesRead"));
            if (event.getStackTrace() != null) {
              var frames = event.getStackTrace().getFrames();
              String applyClass =
                  batch
                      ? TextDocumentChangeSetService.class.getName()
                      : TextChangeSetService.class.getName();
              for (int i = 1; i < frames.size(); i++) {
                var method = frames.get(i).getMethod();
                if (!method.getType().getName().equals(applyClass)
                    || !method.getName().equals("apply")) continue;
                var caller = frames.get(i - 1);
                String site = frames.get(i).getLineNumber() + " " + caller.getMethod().getName();
                applyReads
                    .computeIfAbsent(site, key -> new ArrayList<>())
                    .add(event.getDuration().toNanos());
                break;
              }
            }
          } else {
            writes++;
            writeBytes += event.getLong("bytesWritten");
          }
        }
        row.put("applyFileReadNanosByCallSite", applyReads);
        row.put("fileReadEvents", reads);
        row.put("fileWriteEvents", writes);
        row.put("readBytes", readBytes);
        row.put("writeBytes", writeBytes);
      }
      if (status.equals("APPLIED"))
        assertEquals(
            after,
            Files.readString(
                root.resolve("a.txt"),
                scenario.equals("cp932")
                    ? java.nio.charset.Charset.forName("windows-31j")
                    : java.nio.charset.StandardCharsets.UTF_8));
      String expected =
          switch (scenario) {
            case "ambiguous" -> "REJECTED";
            case "conflict" -> "STALE";
            case "applyFailure", "rollback" -> "ROLLED_BACK";
            case "unknown" -> "UNKNOWN";
            default -> "APPLIED";
          };
      assertEquals(expected, status, scenario);
      row.put("attempts", 1);
      row.put("expectedOutcomes", 1);
      row.put("successfulApplies", status.equals("APPLIED") ? 1 : 0);
      row.put("status", status);
      row.put("outputJsonBytes", output);
      row.put("diffBytes", diff);
      row.put("toolCalls", calls);
      rows.add(row);
    }
    Path output =
        Path.of(System.getProperty("fileWrite.output", "target/file-write-benchmark.json"));
    Files.createDirectories(output.toAbsolutePath().getParent());
    json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), rows);
  }

  @Test
  @EnabledIfSystemProperty(named = "fileWrite.largeDocumentSamples", matches = "[1-9][0-9]*")
  void measureLargeDocumentApply() throws Exception {
    int samples = Integer.getInteger("fileWrite.largeDocumentSamples");

    Path root = Files.createDirectory(temporary.resolve("large-document")).toRealPath();
    String before = "before\n" + "unchanged document line\n".repeat(8000);
    String after = "after\n" + "unchanged document line\n".repeat(8000);
    var operations = new ArrayList<TextDocumentChangeSetService.Operation>();
    for (int file = 0; file < 16; file++) {
      operations.add(
          new TextDocumentChangeSetService.Operation(
              "UPDATE",
              "file-" + file + ".txt",
              null,
              null,
              null,
              TextDocumentTransaction.hash(before),
              List.of(new TextChangeSetService.Edit("before\n", "after\n"))));
    }

    var dataSource =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + root.resolve("db"));
    var service =
        new TextDocumentChangeSetService(
            new TextChangeSetRepository(dataSource), Clock.systemUTC());
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
    var owner =
        new AgentRunContext("large-document", "session", root, UUID.randomUUID().toString());
    var request = new TextDocumentChangeSetService.Request(operations);
    var elapsed = new ArrayList<Long>();
    long payloadBytes = 0;
    for (int sample = -2; sample < samples; sample++) {
      for (var op : operations) Files.writeString(root.resolve(op.path()), before);
      var proposal = service.propose(owner, request);
      if (sample == 0) {
        payloadBytes =
            jdbc.sql(
                    "SELECT length(CAST(payload AS BLOB)) FROM text_document_change_sets WHERE id=?")
                .param(proposal.id())
                .query(Long.class)
                .single();
      }
      long started = System.nanoTime();
      var receipt = service.apply(owner, proposal.id(), proposal.proposalSha256());
      long duration = System.nanoTime() - started;
      assertEquals("APPLIED", receipt.status());
      assertTrue(receipt.currentMatches());
      assertEquals("CLEANED", receipt.phase());
      for (var op : operations) assertEquals(after, Files.readString(root.resolve(op.path())));
      if (sample >= 0) elapsed.add(duration);
    }

    Path output =
        Path.of(System.getProperty("fileWrite.output", "target/file-write-large-document.json"));
    Files.createDirectories(output.toAbsolutePath().getParent());
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            output.toFile(),
            Map.of(
                "files", operations.size(), "payloadBytes", payloadBytes, "applyNanos", elapsed));
  }

  @Test
  @EnabledIfSystemProperty(named = "fileWrite.diffPageSamples", matches = "[1-9][0-9]*")
  void measureDocumentDiffPages() throws Exception {
    int samples = Integer.getInteger("fileWrite.diffPageSamples");
    Path root = Files.createDirectory(temporary.resolve("diff-pages")).toRealPath();
    String secret = "benchmark-secret-value";
    String content = "password=" + secret + "\n" + "unchanged document line 😀\n".repeat(1900);
    var operations = new ArrayList<TextDocumentChangeSetService.Operation>();
    for (int file = 0; file < 4; file++) {
      String path = "file-" + file + ".txt";
      Files.writeString(root.resolve(path), content);
      operations.add(
          new TextDocumentChangeSetService.Operation(
              "RENAME", path, "renamed-" + file + ".txt", content, null));
    }
    var repository =
        new TextChangeSetRepository(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("db")));
    var owner = new AgentRunContext("diff-pages", "session", root, UUID.randomUUID().toString());
    var proposal =
        new TextDocumentChangeSetService(repository, Clock.systemUTC())
            .propose(owner, new TextDocumentChangeSetService.Request(operations));
    var elapsed = new ArrayList<Long>();
    int pages = 0;
    String full = "";
    for (int sample = -2; sample < samples; sample++) {
      var service = new TextDocumentChangeSetService(repository, Clock.systemUTC());
      var received = new StringBuilder();
      int offset = 0;
      pages = 0;
      long started = System.nanoTime();
      do {
        var page = service.diffPage(owner, proposal.id(), proposal.proposalSha256(), offset, 16384);
        received.append(page.diff());
        offset = page.info().nextOffset();
        pages++;
      } while (offset >= 0);
      long duration = System.nanoTime() - started;
      full = received.toString();
      assertTrue(pages > 1);
      assertTrue(full.contains("[REDACTED]"));
      assertFalse(full.contains(secret));
      assertEquals(
          proposal.diffInfo().totalBytes(),
          full.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      if (sample >= 0) elapsed.add(duration);
    }
    Path output =
        Path.of(System.getProperty("fileWrite.output", "target/file-write-diff-pages.json"));
    Files.createDirectories(output.toAbsolutePath().getParent());
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            output.toFile(),
            Map.of(
                "pages",
                pages,
                "diffBytes",
                proposal.diffInfo().totalBytes(),
                "diffSha256",
                TextDocumentTransaction.hash(full),
                "pagingNanos",
                elapsed));
  }
}
