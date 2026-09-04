package dev.mikoto2000.rei.summarize.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.summarize.SummaryResult;
import dev.mikoto2000.rei.summarize.WebPageSummarizerService;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import picocli.CommandLine;

class SummarizeCommandTest {

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

  private static final class RecordingSummarizer implements WebPageSummarizerService {
    private final String summary;
    private URI uri;
    private int calls;

    RecordingSummarizer(String summary) {
      this.summary = summary;
    }

    @Override
    public SummaryResult summarize(URI uri) {
      calls++;
      this.uri = uri;
      return new SummaryResult(uri, summary, null);
    }
  }
}
