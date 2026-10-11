package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.bluesky.BlueskyReplyConversationRepository;
import dev.mikoto2000.rei.bluesky.command.BskyCommand;
import dev.mikoto2000.rei.interest.InterestUpdateService;
import dev.mikoto2000.rei.interest.command.InterestCommand;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public final class SocialCommandsController {
  private final BlueskyReplyConversationRepository history;private final InterestUpdateService interests;private final IntegrationRunSubmitService runs;
  public SocialCommandsController(BlueskyReplyConversationRepository history,InterestUpdateService interests,IntegrationRunSubmitService runs){this.history=history;this.interests=interests;this.runs=runs;}
  public record Text(String text) {}
  public record Reply(String projectId,String sessionId,String target,String text) implements StrictApiRequest {}
  public record Scope(String projectId,String sessionId) implements StrictApiRequest {}
  @GetMapping("/bluesky/history-users") public Text users(){return text(BskyCommand.reportHistoryUsers(history));}
  @GetMapping("/bluesky/history/{handle}") public Text history(@PathVariable String handle,@RequestParam(defaultValue="20") int limit){required(handle);return text(BskyCommand.reportHistory(history,handle,limit));}
  @PostMapping("/bluesky/replies") public ResponseEntity<BackgroundRunController.AcceptedRunResponse> reply(@RequestBody Reply request){if(request==null)throw new IllegalArgumentException("Reply required");required(request.projectId());required(request.target());if(request.text()!=null)required(request.text());return accepted(runs.replyBluesky(request.projectId(),request.sessionId(),request.target(),request.text()));}
  @GetMapping("/interest/commands/list") public Text interests(@RequestParam(defaultValue="24") int hours){return text(InterestCommand.reportList(interests,hours));}
  @PostMapping("/interest/commands/discover") public ResponseEntity<BackgroundRunController.AcceptedRunResponse> discover(@RequestBody Scope request){if(request==null)throw new IllegalArgumentException("Project required");required(request.projectId());return accepted(runs.discoverInterests(request.projectId(),request.sessionId()));}
  private static void required(String value){if(value==null||value.isBlank()||value.length()>16384||value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid argument");}
  private static Text text(String value){return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));}
  private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(dev.mikoto2000.rei.core.chat.AgentRunContext run){return ResponseEntity.accepted().location(java.net.URI.create("/api/v1/runs/"+run.runId())).body(new BackgroundRunController.AcceptedRunResponse(run.runId()));}
}
