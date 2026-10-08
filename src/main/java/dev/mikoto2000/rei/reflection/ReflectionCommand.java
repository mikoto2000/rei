package dev.mikoto2000.rei.reflection;

import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine.*;

@Component
@Command(name="reflection",description="保存された目標・タスク・実行の証拠とレビュー提案を確認します")
public class ReflectionCommand implements java.util.concurrent.Callable<Integer> {
  private final GoalReflectionRepository repository;
  private final GoalReflectionService service;
  private final ProjectService projects;
  private RunReflectionRepository runReflections;
  private RunReflectionService runService;
  private VerifiedReflectionMemoryService promotion;
  private ReflectionLessonService lessons;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setLessons(ReflectionLessonService lessons){this.lessons=lessons;}
  @Option(names="--revision",defaultValue="-1") long revision;
  @Option(names="--evidence-hash") String evidenceHash;
  @Option(names="--note") String note;
  @Option(names="--source-kind",defaultValue="RUN") String sourceKind;
  @Option(names="--kind",defaultValue="FAILURE_PATTERN") ReflectionLessonService.Kind kind;
  @Option(names="--evidence",split=",") java.util.List<String> evidence;
  @Option(names="--observation") String observation;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setPromotion(VerifiedReflectionMemoryService promotion){this.promotion=promotion;}
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setRunService(RunReflectionService runService){this.runService=runService;}
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setRunReflections(RunReflectionRepository runReflections){this.runReflections=runReflections;}
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  @Parameters(index="0",arity="0..1",defaultValue="list",paramLabel="list|show|collect|runs|run|collect-run|promote") String action;
  @Parameters(index="1",arity="0..1",paramLabel="reflectionId|goalId") String id;
  private java.io.PrintWriter output;
  public ReflectionCommand(){this(null,null,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public ReflectionCommand(GoalReflectionRepository repository,GoalReflectionService service,ProjectService projects){this.repository=repository;this.service=service;this.projects=projects;}
  public void setShellOutput(java.io.PrintWriter output){this.output=output;}
  @Override public Integer call() {
    var writer=output==null?spec.commandLine().getOut():output;
    try {
      String project=projects.currentContext().id();Object result=switch(action) {
        case "list" -> repository.list(project);
        case "show" -> repository.get(project,requiredId());
        case "collect" -> service.collect(project,requiredId());
        case "runs" -> runReflections.list(project);
        case "run" -> runReflections.get(project,requiredId());
        case "collect-run" -> runService.collect(project,projects.currentSessionId(),requiredId());
        case "promote" -> {if(promotion==null)throw new IllegalStateException("Verified memory promotion is unavailable");yield promotion.promote(project,requiredId());}
        case "lessons" -> lessonService().list(lessonOwner());
        case "lesson" -> lessonService().get(lessonOwner(),requiredId());
        case "lesson-observe" -> lessonService().observe(lessonOwner(),sourceKind,requiredId());
        case "lesson-observe-correction" -> lessonService().observeCorrection(lessonOwner(),requiredId(),observation,note);
        case "lesson-propose" -> lessonService().propose(lessonOwner(),kind,note,evidence);
        case "lesson-validate" -> lessonService().validate(lessonOwner(),requiredId(),revision,evidenceHash,note);
        case "lesson-counterexample" -> lessonService().counterexample(lessonOwner(),requiredId(),revision,observation,note);
        case "lesson-correct" -> lessonService().correct(lessonOwner(),requiredId(),revision,note);
        case "lesson-forget" -> lessonService().forget(lessonOwner(),requiredId(),revision);
        default -> throw new IllegalArgumentException("Use /reflection list|show|collect|runs|run|collect-run|promote [id]");
      };
      writer.println(dev.mikoto2000.rei.event.CredentialRedactor.redact(String.valueOf(result)));return 0;
    } catch(RuntimeException error){writer.println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage()));return 2;}
    finally {writer.flush();}
  }
  private String requiredId(){if(id==null||id.isBlank())throw new IllegalArgumentException("Reflection ID or Goal ID is required");return id;}
  private ReflectionLessonService lessonService(){if(lessons==null)throw new IllegalStateException("Reflection lessons are disabled");return lessons;}
  private dev.mikoto2000.rei.core.chat.AgentRunContext lessonOwner(){var project=projects.currentContext();return new dev.mikoto2000.rei.core.chat.AgentRunContext("human-reflection",projects.currentSessionId(),project.root(),project.id());}
}
