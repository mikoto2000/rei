package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

@Component
@Command(name="new", description="次の送信から新しい会話を開始します", mixinStandardHelpOptions=true)
public final class NewConversationCommand implements Runnable {
  private final ShellConversationService conversations;
  @Spec private picocli.CommandLine.Model.CommandSpec spec;
  public NewConversationCommand() { this(null); }
  @org.springframework.beans.factory.annotation.Autowired
  public NewConversationCommand(ShellConversationService conversations) { this.conversations = conversations; }
  @Override public void run() {
    if (conversations == null) throw new IllegalStateException("Shell session runtime unavailable");
    conversations.newConversation();
    spec.commandLine().getOut().println("New conversation: the next message will create a session.");
  }
}
