package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.activity.*;
import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.core.project.*;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Explicit journal controls; no action/method selector is supplied to the Backend. */
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/activity/commands")
public final class ActivityCommandsController {
  private final ActivityCommand activity;private final ProjectRegistry registry;private final ProjectService projects;private final IntegrationRunSubmitService runs;
  public record Text(String text) {}
  public record Enable(Boolean enabled,Long expectedRevision) implements StrictApiRequest {}
  public record Configure(PeriodCoaching.Settings settings,Long expectedRevision) implements StrictApiRequest {}
  public record Date(String date) implements StrictApiRequest {}
  public record Scope(String projectId,String sessionId) implements StrictApiRequest {}
  public ActivityCommandsController(ActivityCommand activity,ProjectRegistry registry,ProjectService projects,IntegrationRunSubmitService runs){this.activity=activity;this.registry=registry;this.projects=projects;this.runs=runs;}
  @GetMapping("/timeline") public Text timeline(@RequestParam(defaultValue="today") String date,@RequestParam(defaultValue="false") boolean verbose){date(date);return text(activity.reportTimeline(date,verbose));}
  @PostMapping("/pause") public Text pause(){return text(activity.reportPause());}
  @PostMapping("/resume") public Text resume(){return text(activity.reportResume());}
  @GetMapping("/summary") public Text summary(@RequestParam(defaultValue="today") String date){date(date);return text(activity.reportSummary(date));}
  @GetMapping("/weekly") public Text weekly(@RequestParam(defaultValue="today") String date){date(date);return text(activity.reportWeekly(date));}
  @GetMapping("/monthly") public Text monthly(@RequestParam(defaultValue="today") String date){date(date);return text(activity.reportMonthly(date));}
  @GetMapping("/context") public Text context(@RequestParam String projectId,@RequestParam(defaultValue="today") String date){
    date(date);var project=registry.resolveById(projectId).orElseThrow(()->new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    try(var scope=ProjectClientScope.open(projects.newClient())){projects.cd(project.root().toString());return text(activity.reportContext(date));}
  }
  @GetMapping("/coaching/status") public Text coachingStatus(){return text(activity.reportCoachingStatus());}
  @PostMapping("/coaching/enabled") public Text enabled(@RequestBody Enable request){if(request==null||request.enabled()==null)throw new IllegalArgumentException("Enabled flag required");return text(activity.reportCoachingEnabled(request.enabled(),revision(request.expectedRevision())));}
  @PostMapping("/coaching/settings") public Text configure(@RequestBody Configure request){if(request==null||request.settings()==null||request.settings().enabled())throw new IllegalArgumentException("Save disabled criteria before explicit enable");return text(activity.reportCoachingConfigure(request.settings(),revision(request.expectedRevision())));}
  @PostMapping("/coaching/weekly") public Text coachingWeekly(@RequestBody Date request){if(request==null)throw new IllegalArgumentException("Date request required");date(request.date());return text(activity.reportCoachingWeekly(request.date()));}
  @PostMapping("/coaching/monthly") public Text coachingMonthly(@RequestBody Date request){if(request==null)throw new IllegalArgumentException("Date request required");date(request.date());return text(activity.reportCoachingMonthly(request.date()));}
  @GetMapping("/behavior/status") public Text behaviorStatus(){return text(activity.reportBehaviorStatus());}
  @PostMapping("/behavior/enabled") public Text behaviorEnabled(@RequestBody Enable request){if(request==null||request.enabled()==null)throw new IllegalArgumentException("Enabled flag required");return text(activity.reportBehaviorEnabled(request.enabled()));}
  @GetMapping("/behavior/evaluate") public Text behaviorEvaluate(){return text(activity.reportBehaviorEvaluation());}
  @GetMapping("/behavior/uncertain") public Text uncertain(@RequestParam(defaultValue="20") int top){return text(activity.reportUncertain(top));}
  @PostMapping("/behavior/suggest-rules") public ResponseEntity<BackgroundRunController.AcceptedRunResponse> suggestBehavior(@RequestBody Scope request){scope(request);return accepted(runs.suggestBehaviorRules(request.projectId(),request.sessionId()));}
  @GetMapping("/classification/status") public Text classificationStatus(){return text(activity.reportClassificationStatus());}
  @PostMapping("/classification/reload") public Text reload(){return text(activity.reportClassificationReload());}
  @GetMapping("/classification/unknowns") public Text unknowns(@RequestParam(defaultValue="20") int top){return text(activity.reportUnknown(top));}
  @GetMapping("/classification/rules") public Text rules(){return text(activity.reportRules());}
  @PostMapping("/classification/suggest-rules") public ResponseEntity<BackgroundRunController.AcceptedRunResponse> suggestClassification(@RequestBody Scope request){scope(request);return accepted(runs.suggestClassificationRules(request.projectId(),request.sessionId()));}
  private static void scope(Scope request){if(request==null||request.projectId()==null||request.projectId().isBlank())throw new IllegalArgumentException("Project required");}
  private static void date(String value){if(value!=null&&(value.isBlank()||value.length()>128||value.chars().anyMatch(Character::isISOControl)))throw new IllegalArgumentException("Invalid date");}
  private static long revision(Long value){if(value==null||value<0||value==Long.MAX_VALUE)throw new IllegalArgumentException("Viewed coaching revision required");return value;}
  private static Text text(String value){return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));}
  private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(dev.mikoto2000.rei.core.chat.AgentRunContext run){return ResponseEntity.accepted().location(URI.create("/api/v1/runs/"+run.runId())).body(new BackgroundRunController.AcceptedRunResponse(run.runId()));}
}
