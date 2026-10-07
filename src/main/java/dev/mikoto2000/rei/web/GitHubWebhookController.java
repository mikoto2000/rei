package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.github.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.boot.autoconfigure.condition.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.io.IOException;
@RestController
@ConditionalOnProperty(name={"rei.web.enabled","rei.github.webhook.enabled"},havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/github")
public class GitHubWebhookController {
  private final GitHubWebhookService service;private final GitHubWebhookProperties properties;
  public GitHubWebhookController(GitHubWebhookService service,GitHubWebhookProperties properties){this.service=service;this.properties=properties;}
  @PostMapping(value="/events",consumes="application/json") public ResponseEntity<GitHubFactRepository.Receipt> receive(HttpServletRequest request)throws IOException {
    int limit=properties.getMaxPayloadBytes();
    if(request.getContentLengthLong()>limit)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    String encoding=request.getHeader("Content-Encoding");if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    byte[] bytes=request.getInputStream().readNBytes(limit+1);
    if(bytes.length>limit)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    return ResponseEntity.accepted().body(service.receive(single(request,"X-GitHub-Delivery"),single(request,"X-GitHub-Event"),single(request,"X-Hub-Signature-256"),bytes));
  }
  private static String single(HttpServletRequest request,String name) {
    var values=Collections.list(request.getHeaders(name));
    if(values.size()!=1||values.getFirst().length()>256)throw new SecurityException("GitHub authentication headers required");return values.getFirst();
  }
  @GetMapping("/mappings") public List<GitHubWebhookService.MappingView> mappings(@RequestParam String projectId,@RequestParam String sessionId){return service.mappings(projectId,sessionId);}
  @GetMapping("/facts/{id}") public GitHubFactRepository.View fact(@PathVariable String id,@RequestParam String projectId,@RequestParam String sessionId){return service.get(projectId,sessionId,id);}
  @GetMapping("/deliveries/{delivery}/facts") public List<GitHubFactRepository.View> facts(@PathVariable String delivery,@RequestParam String projectId,@RequestParam String sessionId){return service.forDelivery(projectId,sessionId,delivery);}
  @ExceptionHandler(SecurityException.class) public ResponseEntity<Void> signatureFailure(){return ResponseEntity.status(401).build();}
  @ExceptionHandler(GitHubFactRepository.CapacityExceeded.class) public ResponseEntity<Void> capacity(){return ResponseEntity.status(507).build();}
  @ExceptionHandler({IOException.class,org.springframework.dao.DataAccessException.class}) public ResponseEntity<Void> unavailable(){return ResponseEntity.status(503).build();}
}
