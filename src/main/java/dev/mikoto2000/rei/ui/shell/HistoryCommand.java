package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.session.Pagination;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionQueryService;
import dev.mikoto2000.rei.conversation.HistoryFormatter;
import dev.mikoto2000.rei.conversation.HistorySearchScope;
import dev.mikoto2000.rei.conversation.HistoryShellService;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** 会話履歴の表示・検索ための利用者コマンド。 */
@Component
@Command(
    name = "history",
    description = "会話履歴の表示・検索",
    mixinStandardHelpOptions = true,
    subcommands = {
      HistoryCommand.ListCommand.class,
      HistoryCommand.ShowCommand.class,
      HistoryCommand.SearchCommand.class
    })
public class HistoryCommand implements Callable<Integer> {
  /** サービス。 */
  private final HistoryShellService service;

  /** 会話一覧。 */
  private final SessionQueryService sessions;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** 形式。 */
  private final HistoryFormatter format = new HistoryFormatter();

  /** 上限。 */
  @Option(names = "--limit", description = "セッションの1ページあたりの表示件数（1〜100）")
  Integer limit;

  /** 続きの取得位置。 */
  @Option(names = "--cursor")
  String cursor;

  /** 所属プロジェクトの識別子。 */
  @Option(names = "--project-id")
  String projectId;

  /** ターミナルへの表示先。 */
  private PrintWriter shellOutput;

  /** 仕様。 */
  @Spec private CommandSpec spec;

  /** 既定の状態でHistoryCommandを初期化する。 */
  public HistoryCommand() {
    service = null;
    sessions = null;
    projects = null;
  }

  /** 指定された値や利用先を使って、HistoryCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public HistoryCommand(
      HistoryShellService service, SessionQueryService sessions, ProjectService projects) {
    this.service = service;
    this.sessions = sessions;
    this.projects = projects;
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(PrintWriter output) {
    shellOutput = output;
  }

  /** ターミナルでの対話で用いるレポートを返す。 */
  private String report(java.util.function.Consumer<HistoryCommand> action) {
    var text = new java.io.StringWriter();
    var command = new HistoryCommand(service, sessions, projects);
    command.setShellOutput(new PrintWriter(text, true));
    action.accept(command);
    return text.toString();
  }

  /** ターミナルでの対話で用いるレポート会話一覧を返す。 */
  public String reportSessions(String project, Integer limit, String cursor) {
    return report(
        command -> {
          command.projectId = project;
          command.limit = limit;
          command.cursor = cursor;
          command.call();
        });
  }

  /** ターミナルでの対話で用いるレポート会話を返す。 */
  public String reportSession(String id, Integer limit, String cursor) {
    return report(command -> command.showSession(id, limit, cursor));
  }

  /** ターミナルでの対話で用いるレポート会話一覧を返す。 */
  public String reportConversations(String reference, int limit, int offset) {
    return report(command -> command.execute(out -> service.list(reference, limit, offset, out)));
  }

  /** ターミナルでの対話で用いるレポート会話を返す。 */
  public String reportConversation(String reference, String conversation, int last, boolean all) {
    return report(command -> command.show(reference, conversation, last, all));
  }

  /** ターミナルでの対話で用いるレポート検索を返す。 */
  public String reportSearch(String query, HistorySearchScope scope, int limit) {
    return report(command -> command.execute(out -> service.search(query, scope, limit, out)));
  }

  /** ターミナルでの対話で用いる出力を返す。 */
  private PrintWriter output() {
    return shellOutput == null ? spec.commandLine().getOut() : shellOutput;
  }

  /** 要求された処理を実行する。 */
  private int execute(Consumer<Consumer<String>> action) {
    var out = output();
    try {
      if (service == null) throw new IllegalStateException("History runtime is unavailable");
      action.accept(out::println);
      return 0;
    } catch (ResourceNotFoundException error) {
      out.println("Session not found.");
      return 2;
    } catch (IllegalArgumentException error) {
      out.println(
          new HistoryFormatter().label(dev.mikoto2000.rei.event.FailureMessages.describe(error)));
      return 2;
    } catch (RuntimeException error) {
      out.println(
          "Cannot read conversation history."
              + " 原因: "
              + dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 1;
    } finally {
      out.flush();
    }
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  private int show(String project, String conversation, int last, boolean all) {
    return execute(out -> service.show(project, conversation, last, all, out));
  }

  /** 会話履歴の表示・検索。 */
  @Override
  public Integer call() {
    return execute(
        out -> {
          var page = sessions.listSessions(projectId, limit, cursor);
          if (page.items().isEmpty()) {
            out.accept("No sessions.");
            return;
          }
          out.accept("SESSION ID  UPDATED  PROJECT  TITLE");
          for (var row : page.items())
            out.accept(
                format.label(row.sessionId())
                    + "  "
                    + format.timestamp(row.updatedAt().toString())
                    + "  "
                    + projectLabel(row.projectId())
                    + "  "
                    + format.label(row.title()));
          if (page.nextCursor() != null)
            out.accept(
                "Next page: /history --limit "
                    + Pagination.limit(limit)
                    + (projectId == null ? "" : " --project-id " + format.label(projectId))
                    + " --cursor "
                    + page.nextCursor());
        });
  }

  /** ターミナルでの対話で用いるプロジェクト表示名を返す。 */
  private String projectLabel(String id) {
    return projects.registeredProjects().stream()
        .filter(p -> p.id().equals(id))
        .findFirst()
        .map(p -> format.label(p.name()) + " [" + p.id() + "]")
        .orElse(format.label(id));
  }

  /** 利用者が内容を確認できるよう、会話を表示する。 */
  private int showSession(String requestedId, Integer limit, String cursor) {
    return execute(
        out -> {
          String id = requestedId;
          if (id == null) {
            Pagination.limit(limit);
            var selectedProject =
                projects.registeredProjects().stream()
                    .filter(project -> project.root().equals(projects.currentProject()))
                    .findFirst();
            if (selectedProject.isEmpty()) {
              out.accept("No sessions.");
              return;
            }
            var latest = sessions.listSessions(selectedProject.get().id(), 1, null);
            if (latest.items().isEmpty()) {
              out.accept("No sessions.");
              return;
            }
            id = latest.items().getFirst().sessionId();
          }
          SessionMetadata row;
          try {
            row = sessions.getSession(id);
          } catch (ResourceNotFoundException error) {
            // Older logs have no admission metadata; preserve explicit conversation lookup.
            if (id.contains(":") && limit == null && cursor == null) {
              service.show(null, id, HistoryShellService.DEFAULT_LAST, false, out);
              return;
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
            out.accept(
                "\nTurn: "
                    + format.label(turn.runId())
                    + "  "
                    + format.timestamp(turn.createdAt().toString()));
            if (turn.userMessage() != null && !turn.userMessage().isBlank())
              out.accept("User:\n" + format.body(turn.userMessage()));
            if (turn.source() != null) out.accept("Source: " + format.label(turn.source()));
            if (turn.metadata().containsKey("severity"))
              out.accept("Severity: " + format.label(turn.metadata().get("severity")));
            out.accept(
                "Rei:\n"
                    + (turn.assistantMessage() == null
                        ? "(No response recorded)"
                        : format.body(turn.assistantMessage())));
          }
          if (page.nextCursor() != null)
            out.accept(
                "Next page: /history show "
                    + format.label(id)
                    + " --limit "
                    + Pagination.limit(limit)
                    + " --cursor "
                    + page.nextCursor());
        });
  }

  /** Conversation一覧を行うための利用者コマンド。 */
  @Command(name = "list", description = "Conversation一覧", mixinStandardHelpOptions = true)
  public static class ListCommand implements Callable<Integer> {
    /** 親。 */
    @ParentCommand HistoryCommand parent;

    /** プロジェクト。 */
    @Option(names = "--project", paramLabel = "PROJECT")
    String project;

    /** 上限。 */
    @Option(names = "--limit")
    int limit = HistoryShellService.DEFAULT_LIST_LIMIT;

    /** 開始位置。 */
    @Option(names = "--offset")
    int offset;

    /** Conversation一覧を行う。 */
    public Integer call() {
      return parent.execute(out -> parent.service.list(project, limit, offset, out));
    }
  }

  /** 会話履歴（ID省略時は現在のプロジェクトの最新Session）を行うための利用者コマンド。 */
  @Command(
      name = "show",
      description = "会話履歴（ID省略時は現在のプロジェクトの最新Session）",
      mixinStandardHelpOptions = true)
  public static class ShowCommand implements Callable<Integer> {
    /** 親。 */
    @ParentCommand HistoryCommand parent;

    /** 会話。 */
    @Parameters(
        index = "0",
        arity = "0..1",
        paramLabel = "CONVERSATION",
        completionCandidates = SessionCompletionCandidates.class)
    String conversation;

    /** プロジェクト。 */
    @Option(names = "--project", paramLabel = "PROJECT")
    String project;

    /** 対象範囲。 */
    @ArgGroup(exclusive = true)
    Range range;

    /** 上限。 */
    @Option(names = "--limit")
    Integer limit;

    /** 続きの取得位置。 */
    @Option(names = "--cursor")
    String cursor;

    /** ターミナルでの対話に必要な対象範囲を扱う。 */
    static class Range {
      /** 直近。 */
      @Option(names = "--last", required = true)
      Integer last;

      /** 全対象。 */
      @Option(names = "--all", required = true)
      boolean all;
    }

    /** 会話履歴（ID省略時は現在のプロジェクトの最新Session）を行う。 */
    public Integer call() {
      if (project == null && range == null) {
        if (conversation == null && cursor != null)
          return parent.execute(
              out -> {
                throw new IllegalArgumentException("--cursor requires a session ID");
              });
        return parent.showSession(conversation, limit, cursor);
      }
      if (limit != null || cursor != null)
        return parent.execute(
            out -> {
              throw new IllegalArgumentException(
                  "--limit/--cursor require a session ID without legacy options");
            });
      return parent.show(
          project,
          conversation,
          range != null && range.last != null ? range.last : HistoryShellService.DEFAULT_LAST,
          range != null && range.all);
    }
  }

  /** 過去会話を検索ための利用者コマンド。 */
  @Command(name = "search", description = "過去会話を検索", mixinStandardHelpOptions = true)
  public static class SearchCommand implements Callable<Integer> {
    /** 親。 */
    @ParentCommand HistoryCommand parent;

    /** 検索条件。 */
    @Parameters(arity = "1..*", paramLabel = "QUERY")
    String[] query;

    /** 上限。 */
    @Option(names = "--limit")
    int limit = HistoryShellService.DEFAULT_SEARCH_LIMIT;

    /** 対象範囲。 */
    @ArgGroup(exclusive = true)
    Scope scope;

    /** ターミナルでの対話に必要な対象範囲を扱う。 */
    static class Scope {
      /** 現在。 */
      @Option(names = "--current", required = true)
      boolean current;

      /** 全対象。 */
      @Option(names = "--all", required = true)
      boolean all;
    }

    /** 過去会話を検索。 */
    public Integer call() {
      var selected =
          scope == null
              ? HistorySearchScope.CURRENT_PROJECT_PREFERRED
              : scope.current
                  ? HistorySearchScope.CURRENT_PROJECT_ONLY
                  : HistorySearchScope.ALL_PROJECTS;
      return parent.execute(
          out -> parent.service.search(String.join(" ", query), selected, limit, out));
    }
  }
}
