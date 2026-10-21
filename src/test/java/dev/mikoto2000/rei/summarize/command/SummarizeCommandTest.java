package dev.mikoto2000.rei.summarize.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.summarize.SummaryResult;
import dev.mikoto2000.rei.summarize.WebPageSummarizerService;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

/** 要約コマンドの結果と状態の契約を検証するテスト。 */
class SummarizeCommandTest {

  /** 情報の要約で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void printsUsageAndDoesNotSummarizeWhenUrlIsMissing() {
    RecordingSummarizer summarizer = new RecordingSummarizer("unused");
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    CommandLine command = new CommandLine(new SummarizeCommand(summarizer));
    command.setErr(new PrintWriter(err, true));

    int exitCode = command.execute();

    assertEquals(2, exitCode);
    assertTrue(err.toString().contains("Usage: /summarize <URL>"));
    assertEquals(0, summarizer.calls);
  }

  /** 不正なURLを受け付けないことを確認する。 */
  @Test
  void rejectsInvalidUrlAndDoesNotSummarize() {
    RecordingSummarizer summarizer = new RecordingSummarizer("unused");
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    CommandLine command = new CommandLine(new SummarizeCommand(summarizer));
    command.setErr(new PrintWriter(err, true));

    int exitCode = command.execute("abc");

    assertEquals(2, exitCode);
    assertTrue(err.toString().contains("URL scheme must be http or https"));
    assertEquals(0, summarizer.calls);
  }

  /** 情報の要約で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void passesHttpUrlToSummarizerAndPrintsSummary() {
    RecordingSummarizer summarizer = new RecordingSummarizer("要約結果");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    CommandLine command = new CommandLine(new SummarizeCommand(summarizer));
    command.setOut(new PrintWriter(out, true));

    int exitCode = command.execute("https://example.com/article");

    assertEquals(0, exitCode);
    assertEquals(URI.create("https://example.com/article"), summarizer.uri);
    assertTrue(out.toString().contains("要約結果"));
  }

  /** 情報の要約で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void narratesSummaryWhenSummarizationSucceeds() {
    RecordingSummarizer summarizer = new RecordingSummarizer("要約結果");
    ChatResponseNarrator narrator = Mockito.mock(ChatResponseNarrator.class);
    CommandLine command = new CommandLine(new SummarizeCommand(summarizer, narrator));

    int exitCode = command.execute("https://example.com/article");

    assertEquals(0, exitCode);
    Mockito.verify(narrator).reset();
    Mockito.verify(narrator).narrateIfCompleted("要約結果");
  }

  /** 検証条件を再現するためのRecordingSummarizer。 */
  private static final class RecordingSummarizer implements WebPageSummarizerService {
    /** 検証に使う要約。 */
    private final String summary;

    /** 検証に使う参照先のURI。 */
    private URI uri;

    /** 検証に使う呼び出し回数。 */
    private int calls;

    /** 検証条件に必要な値を受け取り、RecordingSummarizerを用意する。 */
    RecordingSummarizer(String summary) {
      this.summary = summary;
    }

    /** {@code summarize} の条件を再現するための補助情報を返す。 */
    @Override
    public SummaryResult summarize(URI uri) {
      calls++;
      this.uri = uri;
      return new SummaryResult(uri, summary, null);
    }
  }
}
