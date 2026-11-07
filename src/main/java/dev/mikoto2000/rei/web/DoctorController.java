package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.doctor.DoctorActiveService;
import dev.mikoto2000.rei.doctor.DoctorRequest;
import dev.mikoto2000.rei.doctor.DoctorService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 環境診断をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class DoctorController {
  /** 環境診断。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      matchType = SuppressMatchType.EXACT,
      justification = "Uses the configured diagnostic service for passive inspection.")
  private final DoctorService doctor;

  /** 活動中。 */
  private final DoctorActiveService active;

  /** チャット。 */
  private final BiFunction<ChatRequest, String, ChatResponse> submitChat;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す活動中要求を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param checks 確認結果
   * @param details 詳細一覧
   */
  public record ActiveRequest(
      String projectId, String sessionId, List<String> checks, boolean details)
      implements StrictApiRequest {
    public ActiveRequest {
      checks = checks == null ? null : Collections.unmodifiableList(new ArrayList<>(checks));
    }
  }

  /** 指定された値や利用先を使って、DoctorControllerを初期化する。 */
  public DoctorController(
      DoctorService doctor, DoctorActiveService active, ChatSubmitService chat) {
    this.doctor = doctor;
    this.active = active;
    this.submitChat =
        (request, key) -> {
          var run =
              chat.submit(
                  request.message(), request.projectId(), request.sessionId(), request.mode(), key);
          return new ChatResponse(run.runId(), run.conversationId(), run.runId());
        };
  }

  /** Web APIによる操作で用いるpassiveを返す。 */
  @GetMapping("/api/v1/doctor")
  public Text passive(@RequestParam(defaultValue = "false") boolean details) {
    return new Text(DoctorService.render(doctor.passive(details)));
  }

  /** Web APIによる操作で用いる活動中を返す。 */
  @PostMapping("/api/v1/diagnostics")
  public ResponseEntity<ChatResponse> active(
      @RequestBody ActiveRequest request, @RequestHeader("Idempotency-Key") String key) {
    if (active == null) throw new IllegalStateException("Active diagnosis unavailable");
    if (request == null
        || request.checks() == null
        || request.checks().isEmpty()
        || request.checks().size() > 32
        || key == null
        || !key.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new IllegalArgumentException("Named diagnostic checks and receipt key required");
    var checks = new LinkedHashSet<DoctorRequest.Check>();
    for (String check : request.checks()) {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (check == null || check.length() > 32)
        throw new IllegalArgumentException("Invalid diagnostic check");
      checks.add(DoctorRequest.Check.valueOf(check.toUpperCase(Locale.ROOT)));
    }
    String command =
        "/doctor"
            + (request.details() ? " --details" : "")
            + checks.stream()
                .map(check -> " --check " + check.name().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining());
    // Execution remains inside the normal WEB permission and durable Session/Run admission
    // boundary.
    var response =
        submitChat.apply(
            new ChatRequest(
                command, request.projectId(), request.sessionId(), AgentRunContext.Mode.EXCLUSIVE),
            key);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/runs/" + response.runId()))
        .body(response);
  }
}
