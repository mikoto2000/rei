package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

@Component
@Command(name="new", description="新しい Session を作成して選択します", mixinStandardHelpOptions=true)
public final class NewConversationCommand implements java.util.concurrent.Callable<Integer> {
  private final ShellConversationService conversations;
  @Spec private picocli.CommandLine.Model.CommandSpec spec;
  @Parameters(arity="0..1", paramLabel="TITLE") String title;
  public NewConversationCommand() { this(null); }
  @org.springframework.beans.factory.annotation.Autowired
  public NewConversationCommand(ShellConversationService conversations) { this.conversations = conversations; }
  @Override public Integer call() {
    try {
      var session = conversations.newConversation(title);
      spec.commandLine().getOut().println("Current session: " + session.sessionId());
      return 0;
    } catch (RuntimeException error) {
      spec.commandLine().getErr().println("Cannot create session."); return 1;
    }
  }
}
