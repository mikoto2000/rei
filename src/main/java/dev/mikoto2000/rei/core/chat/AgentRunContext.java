package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 会話の実行で受け渡すエージェント実行文脈を表す。
 *
 * <p>Identity and location are captured before dispatch, never resolved at completion.
 *
 * @param runId 実行の識別子
 * @param conversationId 会話の識別子
 * @param projectRoot 作業対象のルートディレクトリ
 * @param projectId 所属プロジェクトの識別子
 * @param requestSource 依頼を受け付けた入力元
 * @param mode 動作モード
 * @param voiceInput 音声入力からの依頼かどうか
 * @param responseStyle 会話の応答形式
 */
public record AgentRunContext(
    String runId,
    String conversationId,
    Path projectRoot,
    String projectId,
    RequestSource requestSource,
    Mode mode,
    boolean voiceInput,
    ResponseStyle responseStyle) {
  /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentRunContext(
      String runId,
      String conversationId,
      Path projectRoot,
      String projectId,
      RequestSource source,
      Mode mode,
      boolean voiceInput) {
    this(
        runId,
        conversationId,
        projectRoot,
        projectId,
        source,
        mode,
        voiceInput,
        ResponseStyle.NORMAL);
  }

  /**
   * 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。
   *
   * <p>Speech provenance adds recognition safety; RequestSource remains the caller's execution
   * authority.
   */
  public AgentRunContext(
      String runId,
      String conversationId,
      Path projectRoot,
      String projectId,
      RequestSource requestSource,
      Mode mode) {
    this(runId, conversationId, projectRoot, projectId, requestSource, mode, false);
  }

  /** 後続の処理が参照できるよう、音声入力からの依頼かどうかとして扱う。 */
  public AgentRunContext asVoiceInput() {
    return new AgentRunContext(
        runId, conversationId, projectRoot, projectId, requestSource, mode, true, responseStyle);
  }

  /** Preparation never silently acquires a writable mode. */
  public AgentRunContext asReadOnly() {
    return new AgentRunContext(
        runId,
        conversationId,
        projectRoot,
        projectId,
        requestSource,
        Mode.READ_ONLY,
        voiceInput,
        responseStyle);
  }

  /** 会話の実行における依頼を受け付けた入力元の選択肢。 */
  public enum RequestSource {
    /** シェル。 */
    SHELL,
    /** Web経由の入力または調査に使う窓口。 */
    WEB,
    /** MCP経由の入力。 */
    MCP
  }

  /** 会話の実行における動作モードの選択肢。 */
  public enum Mode {
    /** 排他。 */
    EXCLUSIVE,
    /** 参照専用。 */
    READ_ONLY,
    /** 会話。 */
    CONVERSATION
  }

  /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentRunContext(
      String runId,
      String conversationId,
      Path projectRoot,
      String projectId,
      RequestSource requestSource) {
    this(runId, conversationId, projectRoot, projectId, requestSource, Mode.EXCLUSIVE);
  }

  /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentRunContext(String runId, String conversationId, Path projectRoot, String projectId) {
    this(runId, conversationId, projectRoot, projectId, RequestSource.SHELL);
  }

  /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentRunContext(String runId, String conversationId, Path projectRoot) {
    this(runId, conversationId, projectRoot, null);
  }

  /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentRunContext(
      String runId,
      dev.mikoto2000.rei.core.project.ProjectContext project,
      String logicalConversation) {
    this(runId, project.conversationId(logicalConversation), project.root(), project.id());
  }

  /** 受け取った情報からAgentRunContextを作成する。 */
  public AgentRunContext {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(conversationId);
    Objects.requireNonNull(requestSource);
    if (responseStyle == null) responseStyle = ResponseStyle.NORMAL;
    if (mode == null) mode = Mode.EXCLUSIVE; // Legacy persisted ownership records have no mode.
    projectRoot = Objects.requireNonNull(projectRoot).toAbsolutePath().normalize();
  }
}
