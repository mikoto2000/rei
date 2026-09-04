package dev.mikoto2000.rei.summarize.command;

import java.net.URI;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.summarize.SummarizationException;
import dev.mikoto2000.rei.summarize.SummaryResult;
import dev.mikoto2000.rei.summarize.WebPageSummarizerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "summarize", description = "Summarize a web page")
@Component
public class SummarizeCommand implements java.util.concurrent.Callable<Integer> {

  private static final String USAGE = "Usage: /summarize <URL>";

  private WebPageSummarizerService summarizerService;

  @Parameters(index = "0", arity = "0..1", paramLabel = "URL", description = "要約する URL")
  private String url;

  @Spec
  private CommandSpec spec;

  public SummarizeCommand() {
  }

  @Autowired
  public SummarizeCommand(WebPageSummarizerService summarizerService) {
    this.summarizerService = summarizerService;
  }

  @Override
  public Integer call() {
    URI uri = parseUrl();
    if (uri == null) {
      return 2;
    }
    if (summarizerService == null) {
      spec.commandLine().getErr().println("Summarize service is not configured");
      return 1;
    }
    try {
      SummaryResult result = summarizerService.summarize(uri);
      spec.commandLine().getOut().println(result.summary());
      return 0;
    } catch (SummarizationException e) {
      spec.commandLine().getErr().println(errorMessage(e));
      return 1;
    }
  }

  private URI parseUrl() {
    if (url == null || url.isBlank()) {
      spec.commandLine().getErr().println(USAGE);
      return null;
    }
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      spec.commandLine().getErr().println("URL format is invalid");
      return null;
    }
    String scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      spec.commandLine().getErr().println("URL scheme must be http or https");
      return null;
    }
    return uri;
  }

  private String errorMessage(SummarizationException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? "要約に失敗しました: " + e.code() : "要約に失敗しました: " + message;
  }
}
