package dev.mikoto2000.rei.ui.shell;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.core.project.ProjectService;

@Component
@Command(name="history",description="会話履歴の表示・検索",mixinStandardHelpOptions=true,
    subcommands={HistoryCommand.ListCommand.class,HistoryCommand.ShowCommand.class,HistoryCommand.SearchCommand.class})
public class HistoryCommand implements Callable<Integer> {
  private final HistoryShellService service;
  private final SessionQueryService sessions;
  private final ProjectService projects;
  private final HistoryFormatter format = new HistoryFormatter();
  @Option(names="--limit", description="Session page size (1-100)") Integer limit;
  @Option(names="--cursor") String cursor;
  @Option(names="--project-id") String projectId;
  private PrintWriter shellOutput;
  @Spec private CommandSpec spec;
  public HistoryCommand() { service=null; sessions=null; projects=null; }
  @org.springframework.beans.factory.annotation.Autowired
  public HistoryCommand(HistoryShellService service, SessionQueryService sessions, ProjectService projects) {
    this.service=service; this.sessions=sessions; this.projects=projects;
  }
  public void setShellOutput(PrintWriter output) { shellOutput=output; }
  private PrintWriter output() { return shellOutput==null?spec.commandLine().getOut():shellOutput; }
  private int execute(Consumer<Consumer<String>> action) {
    var out=output();
    try {
      if(service==null) throw new IllegalStateException("History runtime is unavailable");
      action.accept(out::println); return 0;
    } catch(ResourceNotFoundException error) {
      out.println("Session not found."); return 2;
    } catch(IllegalArgumentException error) {
      out.println(new HistoryFormatter().label(error.getMessage())); return 2;
    } catch(RuntimeException error) {
      out.println("Cannot read conversation history."); return 1;
    } finally { out.flush(); }
  }
  private int show(String project,String conversation,int last,boolean all) {
    return execute(out->service.show(project,conversation,last,all,out));
  }
  @Override public Integer call() {
    return execute(out -> {
      var page = sessions.listSessions(projectId, limit, cursor);
      if (page.items().isEmpty()) { out.accept("No sessions."); return; }
      out.accept("SESSION ID  UPDATED  PROJECT  TITLE");
      for (var row : page.items()) out.accept(format.label(row.sessionId()) + "  "
          + format.timestamp(row.updatedAt().toString()) + "  " + projectLabel(row.projectId()) + "  " + format.label(row.title()));
      if (page.nextCursor() != null) out.accept("Next page: /history --limit " + Pagination.limit(limit)
          + (projectId == null ? "" : " --project-id " + format.label(projectId)) + " --cursor " + page.nextCursor());
    });
  }
  private String projectLabel(String id) {
    return projects.registeredProjects().stream().filter(p -> p.id().equals(id)).findFirst()
        .map(p -> format.label(p.name()) + " [" + p.id() + "]").orElse(format.label(id));
  }
  private int showSession(String requestedId, Integer limit, String cursor) {
    return execute(out -> {
      String id = requestedId;
      if (id == null) {
        Pagination.limit(limit);
        var selectedProject = projects.registeredProjects().stream()
            .filter(project -> project.root().equals(projects.currentProject())).findFirst();
        if (selectedProject.isEmpty()) { out.accept("No sessions."); return; }
        var latest = sessions.listSessions(selectedProject.get().id(), 1, null);
        if (latest.items().isEmpty()) { out.accept("No sessions."); return; }
        id = latest.items().getFirst().sessionId();
      }
      SessionMetadata row;
      try { row = sessions.getSession(id); }
      catch (ResourceNotFoundException error) {
        // Older logs have no admission metadata; preserve explicit conversation lookup.
        if (id.contains(":") && limit == null && cursor == null) {
          service.show(null, id, HistoryShellService.DEFAULT_LAST, false, out); return;
        }
        throw error;
      }
      var page = sessions.listTurns(id, limit, cursor);
      out.accept("Session: " + format.label(row.sessionId()));
      out.accept("Project: " + projectLabel(row.projectId()));
      out.accept("Title: " + format.label(row.title()));
      out.accept("Created: " + format.timestamp(row.createdAt().toString()));
      out.accept("Updated: " + format.timestamp(row.updatedAt().toString()));
      if (page.items().isEmpty()) out.accept("No turns.");
      for (var turn : page.items()) {
        out.accept("\nTurn: " + format.label(turn.runId()) + "  " + format.timestamp(turn.createdAt().toString()));
        if (turn.userMessage() != null && !turn.userMessage().isBlank()) out.accept("User:\n" + format.body(turn.userMessage()));
        if (turn.source() != null) out.accept("Source: " + format.label(turn.source()));
        if (turn.metadata().containsKey("severity")) out.accept("Severity: " + format.label(turn.metadata().get("severity")));
        out.accept("Rei:\n" + (turn.assistantMessage() == null ? "(No response recorded)" : format.body(turn.assistantMessage())));
      }
      if (page.nextCursor() != null) out.accept("Next page: /history show " + format.label(id)
          + " --limit " + Pagination.limit(limit) + " --cursor " + page.nextCursor());
    });
  }

  @Command(name="list",description="Conversation一覧",mixinStandardHelpOptions=true)
  public static class ListCommand implements Callable<Integer> {
    @ParentCommand HistoryCommand parent;
    @Option(names="--project",paramLabel="PROJECT") String project;
    @Option(names="--limit") int limit=HistoryShellService.DEFAULT_LIST_LIMIT;
    @Option(names="--offset") int offset;
    public Integer call() { return parent.execute(out->parent.service.list(project,limit,offset,out)); }
  }
  @Command(name="show",description="会話履歴（ID省略時は現在のプロジェクトの最新Session）",mixinStandardHelpOptions=true)
  public static class ShowCommand implements Callable<Integer> {
    @ParentCommand HistoryCommand parent;
    @Parameters(index="0",arity="0..1",paramLabel="CONVERSATION",completionCandidates=SessionCompletionCandidates.class) String conversation;
    @Option(names="--project",paramLabel="PROJECT") String project;
    @ArgGroup(exclusive=true) Range range;
    @Option(names="--limit") Integer limit;
    @Option(names="--cursor") String cursor;
    static class Range {
      @Option(names="--last",required=true) Integer last;
      @Option(names="--all",required=true) boolean all;
    }
    public Integer call() {
      if (project == null && range == null) {
        if (conversation == null && cursor != null) return parent.execute(out -> { throw new IllegalArgumentException("--cursor requires a session ID"); });
        return parent.showSession(conversation, limit, cursor);
      }
      if (limit != null || cursor != null) return parent.execute(out -> { throw new IllegalArgumentException("--limit/--cursor require a session ID without legacy options"); });
      return parent.show(project,conversation,range!=null&&range.last!=null?range.last:HistoryShellService.DEFAULT_LAST,range!=null&&range.all);
    }
  }
  @Command(name="search",description="過去会話を検索",mixinStandardHelpOptions=true)
  public static class SearchCommand implements Callable<Integer> {
    @ParentCommand HistoryCommand parent;
    @Parameters(arity="1..*",paramLabel="QUERY") String[] query;
    @Option(names="--limit") int limit=HistoryShellService.DEFAULT_SEARCH_LIMIT;
    @ArgGroup(exclusive=true) Scope scope;
    static class Scope {
      @Option(names="--current",required=true) boolean current;
      @Option(names="--all",required=true) boolean all;
    }
    public Integer call() {
      var selected=scope==null?HistorySearchScope.CURRENT_PROJECT_PREFERRED:
          scope.current?HistorySearchScope.CURRENT_PROJECT_ONLY:HistorySearchScope.ALL_PROJECTS;
      return parent.execute(out->parent.service.search(String.join(" ",query),selected,limit,out));
    }
  }
}
