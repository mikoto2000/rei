package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.googlecalendar.command.ScheduleCommand;
import dev.mikoto2000.rei.task.TaskService;
import dev.mikoto2000.rei.task.command.TaskCommand;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public final class GoogleOperationsController {
  private final GoogleCalendarService calendar;private final TaskService tasks;private final IntegrationRunSubmitService runs;
  public record Text(String text) {}
  public record Scope(String projectId,String sessionId) implements StrictApiRequest {}
  public record Event(String title,String start,String end,String location,String description) implements StrictApiRequest {}
  public record Task(String title,LocalDate due,Integer priority,List<String> tags) implements StrictApiRequest {}
  public GoogleOperationsController(GoogleCalendarService calendar,TaskService tasks,IntegrationRunSubmitService runs){this.calendar=calendar;this.tasks=tasks;this.runs=runs;}
  @GetMapping("/google-calendar/events") public Text events(@RequestParam(required=false) LocalDate date,@RequestParam(required=false) String from,@RequestParam(required=false) String to){optional(from);optional(to);return text(ScheduleCommand.reportList(calendar,date,from,to));}
  @PostMapping("/google-calendar/events") public Text addEvent(@RequestBody Event request){
    if(request==null)throw new IllegalArgumentException("Event required");required(request.title());required(request.start());required(request.end());optional(request.location());optional(request.description());
    return text(ScheduleCommand.reportAdd(calendar,request.title(),request.start(),request.end(),request.location(),request.description()));
  }
  @PostMapping("/google-calendar/authorize") public ResponseEntity<ChatResponse> authorizeCalendar(@RequestBody Scope request,@RequestHeader("Idempotency-Key") String key){scope(request);return accepted(runs.authorizeCalendar(request.projectId(),request.sessionId(),key));}
  @PostMapping("/google-calendar/refresh-token") public ResponseEntity<ChatResponse> refresh(@RequestBody Scope request,@RequestHeader("Idempotency-Key") String key){scope(request);return accepted(runs.refreshCalendar(request.projectId(),request.sessionId(),key));}
  @GetMapping("/google-tasks") public Text tasks(@RequestParam(required=false) Integer priority,@RequestParam(required=false) String tag,@RequestParam(required=false) LocalDate dueBefore){optional(tag);return text(TaskCommand.reportList(tasks,priority,tag,dueBefore));}
  @PostMapping("/google-tasks") public Text addTask(@RequestBody Task request){
    if(request==null)throw new IllegalArgumentException("Task required");required(request.title());var tags=request.tags()==null?List.<String>of():request.tags();if(tags.size()>100)throw new IllegalArgumentException("Too many tags");tags.forEach(GoogleOperationsController::required);
    return text(TaskCommand.reportAdd(tasks,request.title(),request.due(),request.priority()==null?3:request.priority(),tags));
  }
  @PostMapping("/google-tasks/{id}/complete") public Text done(@PathVariable long id){return text(TaskCommand.reportDone(tasks,id));}
  @DeleteMapping("/google-tasks/{id}") public Text delete(@PathVariable long id){return text(TaskCommand.reportDelete(tasks,id));}
  @PostMapping("/google-tasks/authorize") public ResponseEntity<ChatResponse> authorizeTasks(@RequestBody Scope request,@RequestHeader("Idempotency-Key") String key){scope(request);return accepted(runs.authorizeTasks(request.projectId(),request.sessionId(),key));}
  private static void scope(Scope request){if(request==null)throw new IllegalArgumentException("Scope required");required(request.projectId());optional(request.sessionId());}
  private static void required(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("Value required");optional(value);}
  private static void optional(String value){if(value!=null&&(value.length()>16384||value.indexOf('\0')>=0))throw new IllegalArgumentException("Invalid value");}
  private static Text text(String value){return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));}
  private static ResponseEntity<ChatResponse> accepted(AgentRunContext run){return ResponseEntity.accepted().location(URI.create("/api/v1/runs/"+run.runId())).body(new ChatResponse(run.runId(),run.conversationId(),run.runId()));}
}
