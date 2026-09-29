package dev.mikoto2000.rei.paper;

import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

@Component
@Command(
    name = "paper",
    description = "論文検索・要約・翻訳・Library: search/show/summarize/translate/library/import")
public class PaperCommand implements java.util.concurrent.Callable<Integer> {
  private final dev.mikoto2000.rei.application.session.ShellConversationService conversations;
  @Unmatched String[] arguments;
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  public PaperCommand() {
    this(null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public PaperCommand(
      dev.mikoto2000.rei.application.session.ShellConversationService conversations) {
    this.conversations = conversations;
  }

  public Integer call() {
    try {
      String[] args = arguments == null ? new String[0] : arguments;
      PaperCommandRequest.parse(args);
      if (conversations == null) {
        spec.commandLine().getErr().println("Paper runtime unavailable");
        return 2;
      }
      conversations.submit(
          "/paper "
              + java.util.Arrays.stream(args)
                  .map(
                      a -> dev.mikoto2000.rei.core.command.UserInputParser.quote(a, true, (char) 0))
                  .collect(java.util.stream.Collectors.joining(" ")));
      return 0;
    } catch (PaperException e) {
      spec.commandLine().getErr().println(e.getMessage());
      return 2;
    }
  }
}
