package dev.mikoto2000.rei.web;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import dev.mikoto2000.rei.attention.AttentionRepository;

@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/attention")
public class AttentionController {
  private final AttentionRepository repository;
  public AttentionController(AttentionRepository repository){this.repository=repository;}
  @GetMapping public List<AttentionRepository.Item> list(@PathVariable String projectId){return repository.list(projectId);}
  @GetMapping("/{id}") public AttentionRepository.Item show(@PathVariable String projectId,@PathVariable String id){return repository.get(projectId,id);}
  @PostMapping("/{id}/ack") public AttentionRepository.Item acknowledge(@PathVariable String projectId,@PathVariable String id) {
    repository.acknowledge(projectId,id);return repository.get(projectId,id);
  }
}
