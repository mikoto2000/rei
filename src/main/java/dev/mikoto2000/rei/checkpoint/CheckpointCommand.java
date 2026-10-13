package dev.mikoto2000.rei.checkpoint;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;
import dev.mikoto2000.rei.core.project.ProjectService;

@Component
@Command(name="checkpoint",description="現在のタスクを保存、保存量の確認・過去履歴の整理",subcommands={CheckpointCommand.Status.class,CheckpointCommand.Cleanup.class})
public class CheckpointCommand implements Callable<Integer> {
  private final PersistentCheckpointService service;private final ProjectService projects;
  @Spec CommandSpec spec;private java.io.PrintWriter output;
  public CheckpointCommand(){this(null,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public CheckpointCommand(PersistentCheckpointService service,ProjectService projects){this.service=service;this.projects=projects;}
  public void setShellOutput(java.io.PrintWriter output){this.output=output;}
  private int print(java.util.function.Supplier<String> action){
    var writer=output==null?spec.commandLine().getOut():output;
    try{writer.println(action.get());return 0;}
    catch(RuntimeException error){writer.println("[error] "+dev.mikoto2000.rei.event.FailureMessages.describe(error));return 2;}
    finally{writer.flush();}
  }
  @Command(name="status",description="全プロジェクトのチェックポイント保存量と上限を表示")
  public static class Status implements Callable<Integer>{
    @ParentCommand CheckpointCommand parent;
    public Integer call(){return parent.print(()->CheckpointHistoryReport.usage(parent.service.historyUsage()));}
  }
  @Command(name="cleanup",description="過去の保存履歴の整理候補を確認。--apply を付けると整理を実行")
  public static class Cleanup implements Callable<Integer>{
    @ParentCommand CheckpointCommand parent;
    @Option(names="--apply",description="全プロジェクトの整理可能な過去履歴を削除（最新状態・実行中・承認待ちは保護）") boolean apply;
    public Integer call(){return parent.print(()->CheckpointHistoryReport.cleanup(parent.service.cleanupHistory(apply)));}
  }
  public Integer call(){var writer=output==null?spec.commandLine().getOut():output;try {
    var state=service.saveCurrent(projects.currentContext().id(),projects.currentSessionId());
    writer.println("Checkpoint saved: task="+state.taskId()+" revision="+state.revision());return 0;
  }catch(RuntimeException e){writer.println("[error] "+dev.mikoto2000.rei.event.FailureMessages.describe(e));return 2;}finally{writer.flush();}}
}
