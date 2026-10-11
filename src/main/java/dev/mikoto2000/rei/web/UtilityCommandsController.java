package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.reminder.*;
import dev.mikoto2000.rei.reminder.command.ReminderCommand;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public final class UtilityCommandsController {
 private final ReminderService reminders;private final IntegrationRunSubmitService runs;
 public UtilityCommandsController(ReminderService reminders,IntegrationRunSubmitService runs){this.reminders=reminders;this.runs=runs;}
 public record Text(String text){}
 public record Reminder(String message,String at,String target,Integer minutesBefore)implements StrictApiRequest{}
 public record Scope(String projectId,String sessionId)implements StrictApiRequest{}
 @GetMapping("/reminders/commands") public Text list(){return text(ReminderCommand.reportList(reminders));}
 @PostMapping("/reminders/commands") public Text add(@RequestBody Reminder request){if(request==null||request.message()==null||request.message().isBlank()||request.message().length()>16384||request.message().indexOf('\0')>=0)throw new IllegalArgumentException("Reminder required");return text(ReminderCommand.reportAdd(reminders,request.message(),request.at()==null?null:java.time.OffsetDateTime.parse(request.at()),request.target()==null?null:java.time.OffsetDateTime.parse(request.target()),request.minutesBefore()));}
 @DeleteMapping("/reminders/commands/{id}") public Text delete(@PathVariable long id){return text(ReminderCommand.reportDelete(reminders,id));}
 @PostMapping("/briefing/commands/today") public ResponseEntity<BackgroundRunController.AcceptedRunResponse> briefing(@RequestBody Scope request){scope(request);return accepted(runs.briefing(request.projectId(),request.sessionId()));}
 @PostMapping("/projects/{projectId}/work-context/updates") public ResponseEntity<BackgroundRunController.AcceptedRunResponse> work(@PathVariable String projectId,@RequestBody Scope request){scope(request);if(!projectId.equals(request.projectId()))throw new IllegalArgumentException("Project ownership mismatch");return accepted(runs.updateWork(projectId,request.sessionId()));}
 private static void scope(Scope request){if(request==null||request.projectId()==null||request.projectId().isBlank())throw new IllegalArgumentException("Project required");}
 private static Text text(String value){return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));}
 private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(dev.mikoto2000.rei.core.chat.AgentRunContext run){return ResponseEntity.accepted().location(java.net.URI.create("/api/v1/runs/"+run.runId())).body(new BackgroundRunController.AcceptedRunResponse(run.runId()));}
}
