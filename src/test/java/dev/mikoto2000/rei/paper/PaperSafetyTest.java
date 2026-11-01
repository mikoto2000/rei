package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 論文安全性の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class PaperSafetyTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う設定。 */
  PaperProperties config = new PaperProperties();

  /** 検証に使う操作の種別。 */
  PaperOperation op = PaperOperation.local("s");

  /** 通信経路について、非公開対象一覧を受け付けないことを確認する。 */
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
    URI uri = URI.create(url);
    var failure = assertThrows(
        PaperException.class,
        () -> new SafePaperHttpClient(config).get(uri, "application/pdf", 1000, op));
    assertEquals(PaperException.Code.PDF_DOWNLOAD_FAILED, failure.code(), failure.getMessage());
    assertThrows(PaperException.class, () -> SafePaperHttpClient.validateUri(uri));
  }

  /** 論文の調査と整理で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 論文の調査と整理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
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

  /** {@code pdf} の条件を再現するための補助情報を返す。 */
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

  /** 論文の調査と整理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void recognizesHeadingsAndExtractionLimit() throws Exception {
    byte[] bytes = pdf();
    var parsed = new PaperExtractionService(config).extract("t", bytes, op);
    assertEquals("Introduction", parsed.sections().getFirst().heading());
    config.setMaxExtractedChars(2);
    assertThrows(
        PaperException.class, () -> new PaperExtractionService(config).extract("t", bytes, op));
  }

  /** 論文の調査と整理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
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
          /** {@code extract} の条件を再現するための補助情報を返す。 */
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

  /** 論文の調査と整理で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 論文の調査と整理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidResourceConfigFailsEarly() {
    config.setTranslationChunkSize(0);
    assertThrows(IllegalArgumentException.class, config::validate);
  }

  /** 論文の調査と整理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void artifactSizeLimit() {
    config.setMaxPdfBytes(4);
    config.setMaxExtractedChars(1);
    var store = new PaperArtifactStore(dir, config);
    assertThrows(
        PaperException.class,
        () -> store.write(UUID.randomUUID().toString(), "originals", "pdf", new byte[9], op));
  }

  /** 論文の調査と整理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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

  /** 論文の調査と整理で、指定した条件に対する結果と状態の変化を確認する。 */
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
