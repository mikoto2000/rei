package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.conversation.HistoryFormatter;
import dev.mikoto2000.rei.core.project.ProjectService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Component
@Command(name="session", description="会話 Session の一覧・作成・選択", mixinStandardHelpOptions=true,
    subcommands={NewConversationCommand.class, ResumeConversationCommand.class})
public final class SessionCommand implements java.util.concurrent.Callable<Integer> {
  private final ShellConversationService conversations;
  private final SessionQueryService sessions;
  private final ProjectService projects;
  private final HistoryFormatter format = new HistoryFormatter();
  @Spec private CommandSpec spec;
  public SessionCommand() { this(null, null, null); }
  @org.springframework.beans.factory.annotation.Autowired
  public SessionCommand(ShellConversationService conversations, SessionQueryService sessions, ProjectService projects) {
    this.conversations = conversations; this.sessions = sessions; this.projects = projects;
  }
  @Override public Integer call() { return show(); }

  @Command(name="show", description="現在の Session を表示", mixinStandardHelpOptions=true)
  public int show() {
    return display(() -> {
      String id = conversations == null ? null : conversations.currentSessionId();
      var out = spec.commandLine().getOut();
      if (id == null) { out.println("No active session."); return; }
      var row = sessions.getSession(id);
      out.println("Session:");
      out.println("  id: " + format.label(row.sessionId()));
      out.println("  title: " + format.label(row.title()));
      out.println("  projectId: " + format.label(row.projectId()));
      out.println("  createdAt: " + row.createdAt());
      out.println("  updatedAt: " + row.updatedAt());
    });
  }
  @Command(name="list", description="現在の Project の Session を更新日時降順で表示", mixinStandardHelpOptions=true)
  public int list() {
    return display(() -> {
      var out = spec.commandLine().getOut();
      String cursor = null;
      boolean any = false;
      do {
        var page = sessions.listSessions(projects.currentContext().id(), 100, cursor);
        for (var row : page.items()) {
          if (!any) { out.println("Sessions:"); any = true; }
          out.println((row.sessionId().equals(conversations.currentSessionId()) ? "* " : "  ")
              + format.label(row.sessionId()) + "  " + format.label(row.title()));
        }
        cursor = page.nextCursor();
      } while (cursor != null);
      if (!any) out.println("No sessions.");
    });
  }
  private int display(Runnable action) {
    try { action.run(); return 0; }
    catch (ResourceNotFoundException error) { spec.commandLine().getErr().println("Session not found."); return 2; }
    catch (RuntimeException error) { spec.commandLine().getErr().println("Cannot read sessions."); return 1; }
    finally { spec.commandLine().getOut().flush(); }
  }
}
