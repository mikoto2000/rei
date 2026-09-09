package dev.mikoto2000.rei.ui.shell;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;
import dev.mikoto2000.rei.conversation.*;

@Component
@Command(name="history",description="会話履歴の表示・検索",mixinStandardHelpOptions=true,
    subcommands={HistoryCommand.ListCommand.class,HistoryCommand.ShowCommand.class,HistoryCommand.SearchCommand.class})
public class HistoryCommand implements Callable<Integer> {
  private final HistoryShellService service;
  private PrintWriter shellOutput;
  @Spec private CommandSpec spec;
  public HistoryCommand() { service=null; }
  @org.springframework.beans.factory.annotation.Autowired
  public HistoryCommand(HistoryShellService service) { this.service=service; }
  public void setShellOutput(PrintWriter output) { shellOutput=output; }
  private PrintWriter output() { return shellOutput==null?spec.commandLine().getOut():shellOutput; }
  private int execute(Consumer<Consumer<String>> action) {
    var out=output();
    try {
      if(service==null) throw new IllegalStateException("History runtime is unavailable");
      action.accept(out::println); return 0;
    } catch(IllegalArgumentException error) {
      out.println(new HistoryFormatter().label(error.getMessage())); return 2;
    } catch(RuntimeException error) {
      out.println("Cannot read conversation history."); return 1;
    } finally { out.flush(); }
  }
  private int show(String project,String conversation,int last,boolean all) {
    return execute(out->service.show(project,conversation,last,all,out));
  }
  @Override public Integer call() { return show(null,null,HistoryShellService.DEFAULT_LAST,false); }

  @Command(name="list",description="Conversation一覧",mixinStandardHelpOptions=true)
  public static class ListCommand implements Callable<Integer> {
    @ParentCommand HistoryCommand parent;
    @Option(names="--project",paramLabel="PROJECT") String project;
    @Option(names="--limit") int limit=HistoryShellService.DEFAULT_LIST_LIMIT;
    @Option(names="--offset") int offset;
    public Integer call() { return parent.execute(out->parent.service.list(project,limit,offset,out)); }
  }
  @Command(name="show",description="会話履歴（既定は直近50件）",mixinStandardHelpOptions=true)
  public static class ShowCommand implements Callable<Integer> {
    @ParentCommand HistoryCommand parent;
    @Parameters(index="0",arity="0..1",paramLabel="CONVERSATION") String conversation;
    @Option(names="--project",paramLabel="PROJECT") String project;
    @ArgGroup(exclusive=true) Range range;
    static class Range {
      @Option(names="--last",required=true) Integer last;
      @Option(names="--all",required=true) boolean all;
    }
    public Integer call() { return parent.show(project,conversation,range!=null&&range.last!=null?range.last:HistoryShellService.DEFAULT_LAST,range!=null&&range.all); }
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
