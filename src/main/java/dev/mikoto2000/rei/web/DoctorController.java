package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.doctor.*;
import java.net.URI;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public final class DoctorController {
  private final DoctorService doctor;
  private final DoctorActiveService active;
  private final ChatSubmitService chat;
  public record Text(String text) {}
  public record ActiveRequest(String projectId,String sessionId,List<String> checks,boolean details) implements StrictApiRequest {}
  public DoctorController(DoctorService doctor,DoctorActiveService active,ChatSubmitService chat) {this.doctor=doctor;this.active=active;this.chat=chat;}
  @GetMapping("/api/v1/doctor") public Text passive(@RequestParam(defaultValue="false") boolean details) {return new Text(DoctorService.render(doctor.passive(details)));}
  @PostMapping("/api/v1/diagnostics") public ResponseEntity<ChatResponse> active(@RequestBody ActiveRequest request,@RequestHeader("Idempotency-Key") String key) {
    if(active==null)throw new IllegalStateException("Active diagnosis unavailable");
    if(request==null||request.checks()==null||request.checks().isEmpty()||request.checks().size()>32||key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("Named diagnostic checks and receipt key required");
    var checks=new LinkedHashSet<DoctorRequest.Check>();
    for(String check:request.checks()){if(check==null||check.length()>32)throw new IllegalArgumentException("Invalid diagnostic check");checks.add(DoctorRequest.Check.valueOf(check.toUpperCase(Locale.ROOT)));}
    String command="/doctor"+(request.details()?" --details":"")+checks.stream().map(check->" --check "+check.name().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.joining());
    // Execution remains inside the normal WEB permission and durable Session/Run admission boundary.
    var run=chat.submit(command,request.projectId(),request.sessionId(),AgentRunContext.Mode.EXCLUSIVE,key);
    return ResponseEntity.accepted().location(URI.create("/api/v1/runs/"+run.runId())).body(new ChatResponse(run.runId(),run.conversationId(),run.runId()));
  }
}
