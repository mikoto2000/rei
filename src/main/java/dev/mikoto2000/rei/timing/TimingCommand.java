package dev.mikoto2000.rei.timing;
import java.io.PrintWriter;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import static dev.mikoto2000.rei.timing.TimingRecorder.*;

@Component
@Command(name="timing",description="現在のSessionのRun時間内訳を表示",mixinStandardHelpOptions=true)
public class TimingCommand implements java.util.concurrent.Callable<Integer> {
 private final TimingRecorder recorder;private final Supplier<String> project,session;
 @Unmatched String[] arguments;
 @Spec picocli.CommandLine.Model.CommandSpec spec;
 public TimingCommand(){this(null,()->null,()->null);}
 @org.springframework.beans.factory.annotation.Autowired
 public TimingCommand(TimingRecorder recorder,dev.mikoto2000.rei.core.project.ProjectService projects,dev.mikoto2000.rei.application.session.ShellConversationService conversations){this(recorder,()->projects.currentContext().id(),conversations::currentSessionId);}
 public TimingCommand(TimingRecorder recorder,Supplier<String> project,Supplier<String> session){this.recorder=recorder;this.project=project;this.session=session;}
 public Integer call() {
  var out=spec.commandLine().getOut();
  try {
   if(recorder==null||!recorder.enabled()){out.println("時間計測は無効です");return 0;}
   var args=arguments==null?new String[0]:arguments;
   if(args.length>3)throw new IllegalArgumentException();
   boolean details=false;String target="last";boolean selected=false;
   for(var arg:args) {if(arg.equals("--details")){if(details)throw new IllegalArgumentException();details=true;}
    else{if(selected||!arg.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException();target=arg;selected=true;}}
   String selectedSession=session.get();
   if(selectedSession==null){out.println("現在のSessionの計測履歴はありません");return 0;}
   String selectedProject=project.get();
   var run=target.equals("last")?recorder.latest(selectedProject,selectedSession):recorder.snapshot(selectedProject,selectedSession,target);
   if(run.isEmpty()){out.println("計測履歴なし: 未計測・別Session・期限切れ・上限による破棄のいずれかです");return 0;}
   render(out,run.get(),details,recorder.statistics());return 0;
  }catch(RuntimeException unavailable){spec.commandLine().getErr().println("Usage: /timing [last|RUN_ID] [--details]. 計測照会に失敗しました。詳細は非表示です。");return 2;}
  finally{out.flush();}
 }
 static String ms(long value){return String.format(Locale.ROOT,"%.6f ms",value/1_000_000d);}
 private static void render(PrintWriter out,Run run,boolean details,Statistics statistics) {
  out.println("Run "+run.id()+" "+run.status()+" 開始 "+run.startedAt()+" 記録"+(run.incomplete()?"不完全":"完全"));
  out.println("全体経過: "+ms(run.elapsedNanos())+" / 延べ処理: "+ms(run.summary().workNanos()));
  out.println("占有和集合: "+ms(run.summary().occupiedNanos())+" / 重複区間: "+ms(run.summary().overlapNanos())+" / 異カテゴリ重複: "+ms(run.summary().crossCategoryOverlapNanos())+" / 未計測: "+ms(run.summary().unmeasuredNanos()));
  out.println("カテゴリ別占有は重複するため加算できません");
  for(var category:Category.values())out.println(category+": "+ms(run.summary().occupancyNanos().getOrDefault(category,0L))+(run.spans().stream().noneMatch(s->s.category()==category)?" (未実行または未計測)":""));
  for(var metric:Metric.values())out.println(metric+": "+(run.milestones().containsKey(metric)?ms(run.milestones().get(metric)):"未取得"));
  var llm=run.spans().stream().filter(s->s.category()==Category.LLM).toList();
  boolean input=llm.stream().anyMatch(s->s.inputTokens()!=null),output=llm.stream().anyMatch(s->s.outputTokens()!=null);
  out.println("入力tokens: "+(input?llm.stream().filter(s->s.inputTokens()!=null).mapToLong(Span::inputTokens).sum():"未取得")+" / 出力tokens: "+(output?llm.stream().filter(s->s.outputTokens()!=null).mapToLong(Span::outputTokens).sum():"未取得")+" (取得済み試行の延べusage、欠落は0としません)");
  out.println("usage取得試行: input="+llm.stream().filter(s->s.inputTokens()!=null).count()+"/"+llm.size()+" output="+llm.stream().filter(s->s.outputTokens()!=null).count()+"/"+llm.size());
  out.println("生成TPS: "+(llm.stream().anyMatch(s->s.generationTps()!=null)?"取得した試行は詳細に表示":"未取得")+" / サーバー内部prefill・decode・reasoning: 未取得");
  out.println("保持Run/Span: "+statistics.runs()+"/"+statistics.spans()+" / 破棄Run: "+statistics.evictedRuns()+" / 省略Span: "+statistics.droppedSpans());
  if(details)for(var span:run.spans()) {
   out.println("  "+span.id()+" parent="+span.parentId()+" request="+span.requestId()+" attempt="+span.attemptId()+" "+span.category()+" "+span.status()+" start="+ms(span.startNanos())+" end="+(span.endNanos()==null?"未完了":ms(span.endNanos())));
   out.println("    観測 "+span.startedAt()+" 指標(Span開始からns) "+span.milestones()+" input="+(span.inputTokens()==null?"未取得":span.inputTokens())+" output="+(span.outputTokens()==null?"未取得":span.outputTokens())+" generationTPS="+(span.generationTps()==null?"未取得":span.generationTps())+" (Run範囲外の時間は占有に加算しません)");
  }
 }
}
