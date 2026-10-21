package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.attention.AttentionRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 注意をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/attention")
public class AttentionController {
  /** 保存と検索の窓口。 */
  private final AttentionRepository repository;

  /** 配信。 */
  private dev.mikoto2000.rei.attention.AttentionDeliveryService delivery;

  /** Web APIによる操作で使用する配信を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setDelivery(dev.mikoto2000.rei.attention.AttentionDeliveryService delivery) {
    this.delivery = delivery;
  }

  /** 指定された値や利用先を使って、AttentionControllerを初期化する。 */
  public AttentionController(AttentionRepository repository) {
    this.repository = repository;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public List<AttentionRepository.Item> list(@PathVariable String projectId) {
    return repository.list(projectId);
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/{id}")
  public AttentionRepository.Item show(@PathVariable String projectId, @PathVariable String id) {
    return repository.get(projectId, id);
  }

  /** Web APIによる操作で用いるacknowledgeを返す。 */
  @PostMapping("/{id}/ack")
  public AttentionRepository.Item acknowledge(
      @PathVariable String projectId, @PathVariable String id) {
    repository.acknowledge(projectId, id);
    return repository.get(projectId, id);
  }

  /**
   * Web APIによる操作で受け渡す配信要求を表す。
   *
   * @param retry 再試行
   * @param acknowledgeDuplicateRisk acknowledge重複の有無リスク
   */
  public record DeliveryRequest(boolean retry, boolean acknowledgeDuplicateRisk) {}

  /** Web APIによる操作で用いる配信を返す。 */
  @GetMapping("/{id}/delivery")
  public dev.mikoto2000.rei.attention.AttentionDeliveryRepository.Delivery delivery(
      @PathVariable String projectId, @PathVariable String id) {
    return requireDelivery().status(projectId, id);
  }

  /** Web APIによる操作で用いる要求配信を返す。 */
  @PostMapping("/{id}/delivery")
  public dev.mikoto2000.rei.attention.AttentionDeliveryRepository.Delivery requestDelivery(
      @PathVariable String projectId,
      @PathVariable String id,
      @RequestBody DeliveryRequest request) {
    if (request == null) throw new IllegalArgumentException("Delivery request is required");
    return request.retry()
        ? requireDelivery().retry(projectId, id, request.acknowledgeDuplicateRisk())
        : requireDelivery().request(projectId, id);
  }

  /** 条件を満たさない操作を拒否するため、配信の前提条件を確認する。 */
  private dev.mikoto2000.rei.attention.AttentionDeliveryService requireDelivery() {
    if (delivery == null) throw new IllegalArgumentException("Attention delivery is unavailable");
    return delivery;
  }
}
