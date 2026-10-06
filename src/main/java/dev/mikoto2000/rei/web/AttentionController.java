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
  private dev.mikoto2000.rei.attention.AttentionDeliveryService delivery;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setDelivery(dev.mikoto2000.rei.attention.AttentionDeliveryService delivery){this.delivery=delivery;}
  public AttentionController(AttentionRepository repository){this.repository=repository;}
  @GetMapping public List<AttentionRepository.Item> list(@PathVariable String projectId){return repository.list(projectId);}
  @GetMapping("/{id}") public AttentionRepository.Item show(@PathVariable String projectId,@PathVariable String id){return repository.get(projectId,id);}
  @PostMapping("/{id}/ack") public AttentionRepository.Item acknowledge(@PathVariable String projectId,@PathVariable String id) {
    repository.acknowledge(projectId,id);return repository.get(projectId,id);
  }
  public record DeliveryRequest(boolean retry,boolean acknowledgeDuplicateRisk) {}
  @GetMapping("/{id}/delivery") public dev.mikoto2000.rei.attention.AttentionDeliveryRepository.Delivery delivery(@PathVariable String projectId,@PathVariable String id){return requireDelivery().status(projectId,id);}
  @PostMapping("/{id}/delivery") public dev.mikoto2000.rei.attention.AttentionDeliveryRepository.Delivery requestDelivery(@PathVariable String projectId,@PathVariable String id,@RequestBody DeliveryRequest request) {
    if(request==null)throw new IllegalArgumentException("Delivery request is required");
    return request.retry()?requireDelivery().retry(projectId,id,request.acknowledgeDuplicateRisk()):requireDelivery().request(projectId,id);
  }
  private dev.mikoto2000.rei.attention.AttentionDeliveryService requireDelivery(){if(delivery==null)throw new IllegalArgumentException("Attention delivery is unavailable");return delivery;}
}
