package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@org.junit.jupiter.api.Tag("integration")
class PaperSafetyTest {
  @TempDir Path dir;
  PaperProperties config = new PaperProperties();
  PaperOperation op = PaperOperation.local("s");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.0.0.1/a",
        "http://[::1]/a",
        "http://localhost/a",
        "http://2130706433/a",
        "file:///a"
      })
  void transportRejectsPrivateTargets(String url) {
    assertThrows(
        PaperException.class,
        () -> new SafePaperHttpClient(config).get(URI.create(url), "application/pdf", 1000, op));
  }

  @Test
  void resolverValidatesAlreadyResolvedAddresses() throws Exception {
    var executor = new io.netty.util.concurrent.DefaultEventExecutor();
    try (var group = new SafePaperHttpClient.PublicResolverGroup()) {
      var future =
          group
              .getResolver(executor)
              .resolve(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 80));
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> future.get(2, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      executor.shutdownGracefully();
    }
  }

  @Test
  void pdfPageLimitAndEmptyPdf() throws Exception {
    try (var doc = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      doc.addPage(new PDPage());
      doc.addPage(new PDPage());
      doc.save(out);
      config.setMaxPages(1);
      assertThrows(
          PaperException.class,
          () -> new PaperExtractionService(config).extract("t", out.toByteArray(), op));
    }
    try (var doc = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      doc.addPage(new PDPage());
      doc.save(out);
      assertThrows(
          PaperException.class,
          () -> new PaperExtractionService(config).extract("t", out.toByteArray(), op));
    }
  }

  byte[] pdf() throws Exception {
    try (var doc = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      var page = new PDPage();
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(30, 700);
        stream.showText("Introduction");
        stream.newLineAtOffset(0, -20);
        stream.showText("A finding.");
        stream.endText();
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void recognizesHeadingsAndExtractionLimit() throws Exception {
    byte[] bytes = pdf();
    var parsed = new PaperExtractionService(config).extract("t", bytes, op);
    assertEquals("Introduction", parsed.sections().getFirst().heading());
    config.setMaxExtractedChars(2);
    assertThrows(
        PaperException.class, () -> new PaperExtractionService(config).extract("t", bytes, op));
  }

  @Test
  void importThenRestartDoesNotDownloadOrParseAgain() throws Exception {
    var store = new PaperArtifactStore(dir, config);
    var content =
        new PaperContentService(
            store,
            (u, t, m, o) -> {
              throw new AssertionError("network");
            },
            new PaperExtractionService(config),
            config);
    var paper =
        new Paper(
            UUID.randomUUID().toString(),
            "t",
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of());
    content.importPdf(paper, pdf(), op);
    var parser =
        new PaperExtractionService(config) {
          @Override
          public StructuredPaper extract(String t, byte[] b, PaperOperation o) {
            throw new AssertionError("parse");
          }
        };
    var restarted =
        new PaperContentService(
            new PaperArtifactStore(dir, config),
            (u, t, m, o) -> {
              throw new AssertionError();
            },
            parser,
            config);
    assertEquals(
        StructuredPaper.Availability.FULL_TEXT, restarted.content(paper, op).availability());
  }

  @Test
  void arxivAndOaResolution() {
    var paper =
        new Paper(
            null,
            "t",
            List.of(),
            null,
            null,
            null,
            null,
            "2501.12345",
            null,
            null,
            true,
            null,
            "https://example.org/p.pdf",
            List.of());
    assertEquals(
        "https://arxiv.org/pdf/2501.12345",
        new ArxivContentProvider().resolve(paper).orElseThrow().toString());
    assertEquals(
        paper.pdfUrl(), new OpenAccessContentProvider().resolve(paper).orElseThrow().toString());
  }

  @Test
  void invalidResourceConfigFailsEarly() {
    config.setTranslationChunkSize(0);
    assertThrows(IllegalArgumentException.class, config::validate);
  }

  @Test
  void artifactSizeLimit() {
    config.setMaxPdfBytes(4);
    config.setMaxExtractedChars(1);
    var store = new PaperArtifactStore(dir, config);
    assertThrows(
        PaperException.class,
        () -> store.write(UUID.randomUUID().toString(), "originals", "pdf", new byte[9], op));
  }

  @Test
  void toolSchemasAreSeparateAndIncludeContextWithoutAdvertisingIt() {
    var callbacks =
        org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
            .toolObjects(new PaperTools(null))
            .build()
            .getToolCallbacks();
    assertEquals(8, callbacks.length);
    for (var callback : callbacks)
      assertFalse(callback.getToolDefinition().inputSchema().contains("RunExecutionContext"));
  }

  @Test
  void shellQuotesRoundTripWindowsPathAndEmbeddedQuotes() {
    String[] args = {"search", "GUI \"agents\""};
    var encoded =
        Arrays.stream(args)
            .map(a -> dev.mikoto2000.rei.core.command.UserInputParser.quote(a, true, (char) 0))
            .collect(java.util.stream.Collectors.joining(" "));
    assertArrayEquals(args, new dev.mikoto2000.rei.core.command.UserInputParser().split(encoded));
    assertEquals("GUI \"agents\"", PaperCommandRequest.parse(args).query(10).query());
  }
}
