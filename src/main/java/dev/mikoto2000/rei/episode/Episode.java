package dev.mikoto2000.rei.episode;

import java.util.List;
import java.util.Set;

/**
 * 過去の経験の活用で受け渡す経験の記録を表す。
 *
 * <p>An immutable event revision. Evidence belongs to each claim, never to the event status.
 *
 * @param id 識別子
 * @param projectId 所属プロジェクトの識別子
 * @param sessionId 会話の識別子
 * @param revision 改訂番号
 * @param occurredAt occurred日時
 * @param endedAt 終了日時
 * @param status 状態
 * @param title タイトル
 * @param summary 要約
 * @param claims 利用権の確保一覧
 * @param confidence 確信度
 */
public record Episode(
    String id,
    String projectId,
    String sessionId,
    String revision,
    String occurredAt,
    String endedAt,
    Status status,
    String title,
    String summary,
    List<Claim> claims,
    double confidence) {
  /** 過去の経験の活用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Episode(
      String id,
      String projectId,
      String sessionId,
      String revision,
      String occurredAt,
      Status status,
      String title,
      String summary,
      List<Claim> claims,
      double confidence) {
    this(
        id,
        projectId,
        sessionId,
        revision,
        occurredAt,
        null,
        status,
        title,
        summary,
        claims,
        confidence);
  }

  /** 過去の経験の活用における状態の選択肢。 */
  public enum Status {
    /** 入力先進捗。 */
    IN_PROGRESS,
    /** 完了。 */
    COMPLETED,
    /** 未確認。 */
    UNVERIFIED,
    /** 取り下げられた状態。 */
    WITHDRAWN
  }

  /** 過去の経験の活用における根拠の選択肢。 */
  public enum Evidence {
    /** 利用者明示的な。 */
    USER_EXPLICIT,
    /** ツール観測。 */
    TOOL_OBSERVED,
    /** モデル提案。 */
    MODEL_PROPOSAL,
    /** 導出した要約。 */
    DERIVED_SUMMARY,
    /** 未確認。 */
    UNVERIFIED
  }

  /**
   * 過去の経験の活用で受け渡す利用権の確保を表す。
   *
   * @param kind 種類
   * @param text テキスト
   * @param evidence 根拠
   * @param runId 実行の識別子
   * @param speaker 読み上げ先
   * @param sourceId 取得元識別子
   */
  public record Claim(
      String kind, String text, Evidence evidence, String runId, String speaker, String sourceId) {
    /** 過去の経験の活用を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Claim(String kind, String text, Evidence evidence, String runId, String speaker) {
      this(kind, text, evidence, runId, speaker, null);
    }

    /** 受け取った情報からClaimを作成する。 */
    public Claim {
      if (kind == null
          || !Set.of(
                  "background", "alternative", "decision", "reason", "action", "result", "unknown")
              .contains(kind)
          || text == null
          || text.isBlank()
          || text.length() > 2000
          || evidence == null
          || runId == null
          || runId.isBlank()
          || !Set.of("user", "assistant", "tool").contains(speaker))
        throw new IllegalArgumentException("Invalid episode claim");
      if (evidence == Evidence.USER_EXPLICIT && !speaker.equals("user")
          || evidence == Evidence.TOOL_OBSERVED && !speaker.equals("tool"))
        throw new IllegalArgumentException("Evidence does not match speaker");
      if (speaker.equals("tool") && (sourceId == null || sourceId.isBlank()))
        throw new IllegalArgumentException("Tool evidence needs an event ID");
      if (!speaker.equals("tool") && sourceId != null)
        throw new IllegalArgumentException("Conversation evidence uses the real Run ID only");
    }
  }

  /** 受け取った情報からEpisodeを作成する。 */
  public Episode {
    for (var value : List.of(id, projectId, sessionId, revision, occurredAt, title, summary))
      if (value.isBlank() || value.length() > 2000)
        throw new IllegalArgumentException("Invalid episode field");
    java.time.Instant.parse(occurredAt);
    if (endedAt != null
        && java.time.Instant.parse(endedAt).isBefore(java.time.Instant.parse(occurredAt)))
      throw new IllegalArgumentException("Invalid episode period");
    claims = List.copyOf(claims);
    if (status == null
        || claims.isEmpty()
        || claims.size() > 32
        || !Double.isFinite(confidence)
        || confidence < 0
        || confidence > 1) throw new IllegalArgumentException("Invalid episode");
  }
}
