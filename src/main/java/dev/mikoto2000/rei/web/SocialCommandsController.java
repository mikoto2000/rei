package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.bluesky.BlueskyReplyConversationRepository;
import dev.mikoto2000.rei.bluesky.command.BskyCommand;
import dev.mikoto2000.rei.interest.InterestUpdateService;
import dev.mikoto2000.rei.interest.command.InterestCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Socialコマンド一覧をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public final class SocialCommandsController {
  /** 履歴。 */
  private final BlueskyReplyConversationRepository history;

  /** 関心一覧。 */
  private final InterestUpdateService interests;

  /** 実行一覧。 */
  private final IntegrationRunSubmitService runs;

  /** 指定された値や利用先を使って、SocialCommandsControllerを初期化する。 */
  public SocialCommandsController(
      BlueskyReplyConversationRepository history,
      InterestUpdateService interests,
      IntegrationRunSubmitService runs) {
    this.history = history;
    this.interests = interests;
    this.runs = runs;
  }

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す返信を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param target 対象
   * @param text テキスト
   */
  public record Reply(String projectId, String sessionId, String target, String text)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す対象範囲を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   */
  public record Scope(String projectId, String sessionId) implements StrictApiRequest {}

  /** Web APIによる操作で用いる利用者一覧を返す。 */
  @GetMapping("/bluesky/history-users")
  public Text users() {
    return text(BskyCommand.reportHistoryUsers(history));
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  @GetMapping("/bluesky/history/{handle}")
  public Text history(@PathVariable String handle, @RequestParam(defaultValue = "20") int limit) {
    required(handle);
    return text(BskyCommand.reportHistory(history, handle, limit));
  }

  /** Web APIによる操作で用いる返信を返す。 */
  @PostMapping("/bluesky/replies")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> reply(
      @RequestBody Reply request) {
    if (request == null) throw new IllegalArgumentException("Reply required");
    required(request.projectId());
    required(request.target());
    if (request.text() != null) required(request.text());
    return accepted(
        runs.replyBluesky(
            request.projectId(), request.sessionId(), request.target(), request.text()));
  }

  /** Web APIによる操作で用いる関心一覧を返す。 */
  @GetMapping("/interest/commands/list")
  public Text interests(@RequestParam(defaultValue = "24") int hours) {
    return text(InterestCommand.reportList(interests, hours));
  }

  /** Web APIによる操作で用いる利用先の検出を返す。 */
  @PostMapping("/interest/commands/discover")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> discover(
      @RequestBody Scope request) {
    if (request == null) throw new IllegalArgumentException("Project required");
    required(request.projectId());
    return accepted(runs.discoverInterests(request.projectId(), request.sessionId()));
  }

  /** Web APIによる操作に関する必須を処理する。 */
  private static void required(String value) {
    if (value == null || value.isBlank() || value.length() > 16384 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid argument");
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static Text text(String value) {
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));
  }

  /** Web APIによる操作で用いる受付済みを返す。 */
  private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(
      dev.mikoto2000.rei.core.chat.AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(java.net.URI.create("/api/v1/runs/" + run.runId()))
        .body(new BackgroundRunController.AcceptedRunResponse(run.runId()));
  }
}
