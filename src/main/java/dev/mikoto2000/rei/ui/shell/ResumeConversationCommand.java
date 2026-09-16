package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.application.run.*;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

@Component
@Command(name="resume", description="現在の project の Session を明示的に選択します", mixinStandardHelpOptions=true)
public final class ResumeConversationCommand implements java.util.concurrent.Callable<Integer> {
  private final ShellConversationService conversations;
  @Parameters(index="0", paramLabel="SESSION_ID") String sessionId;
  @Spec private picocli.CommandLine.Model.CommandSpec spec;
  public ResumeConversationCommand() { this(null); }
  @org.springframework.beans.factory.annotation.Autowired
  public ResumeConversationCommand(ShellConversationService conversations) { this.conversations = conversations; }
  @Override public Integer call() {
    try {
      conversations.resume(sessionId);
      spec.commandLine().getOut().println("Current session: " + new dev.mikoto2000.rei.conversation.HistoryFormatter().label(sessionId));
      return 0;
    } catch (ResourceNotFoundException error) {
      spec.commandLine().getErr().println("Session not found."); return 2;
    } catch (SessionConflictException error) {
      spec.commandLine().getErr().println("Session belongs to another project. Select that project first."); return 2;
    } catch (RuntimeException error) {
      spec.commandLine().getErr().println("Cannot select session."); return 1;
    }
  }
}
