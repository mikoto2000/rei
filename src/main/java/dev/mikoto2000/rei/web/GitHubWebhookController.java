package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.github.GitHubFactRepository;
import dev.mikoto2000.rei.github.GitHubWebhookProperties;
import dev.mikoto2000.rei.github.GitHubWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Git連携先Webhook通知をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.github.webhook.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/github")
public class GitHubWebhookController {
  /** サービス。 */
  private final GitHubWebhookService service;

  /** この機能の動作設定。 */
  private final GitHubWebhookProperties properties;

  /** 指定された値や利用先を使って、GitHubWebhookControllerを初期化する。 */
  public GitHubWebhookController(GitHubWebhookService service, GitHubWebhookProperties properties) {
    this.service = service;
    this.properties = properties;
  }

  /** Web APIによる操作で用いる受信を返す。 */
  @PostMapping(value = "/events", consumes = "application/json")
  public ResponseEntity<GitHubFactRepository.Receipt> receive(HttpServletRequest request)
      throws IOException {
    int limit = properties.getMaxPayloadBytes();
    if (request.getContentLengthLong() > limit)
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE);
    String encoding = request.getHeader("Content-Encoding");
    if (encoding != null && !encoding.equalsIgnoreCase("identity"))
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    byte[] bytes = request.getInputStream().readNBytes(limit + 1);
    if (bytes.length > limit)
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE);
    return ResponseEntity.accepted()
        .body(
            service.receive(
                single(request, "X-GitHub-Delivery"),
                single(request, "X-GitHub-Event"),
                single(request, "X-Hub-Signature-256"),
                bytes));
  }

  /** Web APIによる操作で用いる単一を返す。 */
  private static String single(HttpServletRequest request, String name) {
    var values = Collections.list(request.getHeaders(name));
    if (values.size() != 1 || values.getFirst().length() > 256)
      throw new SecurityException("GitHub authentication headers required");
    return values.getFirst();
  }

  /** Web APIによる操作で用いる対応付け一覧を返す。 */
  @GetMapping("/mappings")
  public List<GitHubWebhookService.MappingView> mappings(
      @RequestParam String projectId, @RequestParam String sessionId) {
    return service.mappings(projectId, sessionId);
  }

  /** Web APIによる操作で用いる事実を返す。 */
  @GetMapping("/facts/{id}")
  public GitHubFactRepository.View fact(
      @PathVariable String id, @RequestParam String projectId, @RequestParam String sessionId) {
    return service.get(projectId, sessionId, id);
  }

  /** Web APIによる操作で用いる事実一覧を返す。 */
  @GetMapping("/deliveries/{delivery}/facts")
  public List<GitHubFactRepository.View> facts(
      @PathVariable String delivery,
      @RequestParam String projectId,
      @RequestParam String sessionId) {
    return service.forDelivery(projectId, sessionId, delivery);
  }

  /** Web APIによる操作で用いる署名失敗情報を返す。 */
  @ExceptionHandler(SecurityException.class)
  public ResponseEntity<Void> signatureFailure() {
    return ResponseEntity.status(401).build();
  }

  /** Web APIによる操作で用いる許容件数を返す。 */
  @ExceptionHandler(GitHubFactRepository.CapacityExceeded.class)
  public ResponseEntity<Void> capacity() {
    return ResponseEntity.status(507).build();
  }

  /** Web APIによる操作で用いる利用不可を返す。 */
  @ExceptionHandler({IOException.class, org.springframework.dao.DataAccessException.class})
  public ResponseEntity<Void> unavailable() {
    return ResponseEntity.status(503).build();
  }
}
