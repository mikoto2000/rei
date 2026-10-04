package dev.mikoto2000.rei.web;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;

/** Human-facing API only; deciding does not dispatch or execute the Tool. */
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/approvals")
public class ApprovalController {
  private final ToolApprovalRepository repository;
  public ApprovalController(ToolApprovalRepository repository){this.repository=repository;}
  @GetMapping public List<ToolApprovalRepository.Request> list(@PathVariable String projectId){return repository.list(projectId);}
  @GetMapping("/{id}") public ToolApprovalRepository.Request show(@PathVariable String projectId,@PathVariable String id){return repository.get(projectId,id);}
  public record Decision(Boolean approved){}
  @PostMapping("/{id}/decision") public ToolApprovalRepository.Request decide(@PathVariable String projectId,@PathVariable String id,@RequestBody Decision decision) {
    if(decision.approved()==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"approved is required");
    return repository.decide(projectId,id,decision.approved());
  }
}
