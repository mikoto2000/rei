package dev.mikoto2000.rei.externalagent;

import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

/** Thin adapter over the existing project/session queue. */
@Component
@Command(name="material-review", description="初学者視点の静的教材レビュー: beginner --root DIR --entry PAGE")
public class BeginnerReviewCommand implements java.util.concurrent.Callable<Integer> {
  private final dev.mikoto2000.rei.application.session.ShellConversationService conversations;
  @Unmatched String[] arguments;
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  public BeginnerReviewCommand() { this(null); }
  @org.springframework.beans.factory.annotation.Autowired
  public BeginnerReviewCommand(dev.mikoto2000.rei.application.session.ShellConversationService conversations) { this.conversations = conversations; }
  public Integer call() {
    try {
      var args = arguments == null ? new String[0] : arguments;
      BeginnerReviewRequest.parse(args);
      if (conversations == null) throw new IllegalArgumentException("Material review runtime unavailable");
      conversations.submit("/material-review " + java.util.Arrays.stream(args)
          .map(a -> dev.mikoto2000.rei.core.command.UserInputParser.quote(a, true, (char)0))
          .collect(java.util.stream.Collectors.joining(" ")));
      return 0;
    } catch (IllegalArgumentException error) { spec.commandLine().getErr().println(error.getMessage()); return 2; }
  }
}
