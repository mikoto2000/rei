package dev.mikoto2000.rei.workcontext;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 作業文脈の引き継ぎで受け渡す作業文脈を表す。
 *
 * <p>Project work state, separate from chat history, task execution and personal memory.
 *
 * @param projectId 所属プロジェクトの識別子
 * @param revision 改訂番号
 * @param createdAt 作成日時
 * @param updatedAt 更新日時
 * @param git Gitの情報
 * @param items 項目一覧
 * @param processedRuns 処理済み実行一覧
 */
public record WorkContext(
    String projectId,
    long revision,
    Instant createdAt,
    Instant updatedAt,
    GitState git,
    List<Item> items,
    Set<String> processedRuns) {
  /** 受け取った情報からWorkContextを作成する。 */
  public WorkContext {
    items = List.copyOf(items);
    processedRuns = Set.copyOf(processedRuns);
  }

  /** 作業文脈の引き継ぎにおける種類の選択肢。 */
  public enum Kind {
    /** 目的。 */
    PURPOSE,
    /** 現在作業。 */
    CURRENT_WORK,
    /** 判断結果。 */
    DECISION,
    /** 完了作業。 */
    COMPLETED_WORK,
    /** 待機中。 */
    PENDING,
    /** 確認結果。 */
    VERIFICATION,
    /** 作業を妨げる課題。 */
    BLOCKER,
    /** 次操作。 */
    NEXT_ACTION,
    /** 成果物。 */
    ARTIFACT
  }

  /** 作業文脈の引き継ぎにおける状態の選択肢。 */
  public enum Status {
    /** 開始。 */
    OPEN,
    /** 完了。 */
    COMPLETED,
    /** 取り下げられた状態。 */
    WITHDRAWN,
    /** 置き換え済みの内容。 */
    SUPERSEDED,
    /** 未確認。 */
    UNCONFIRMED
  }

  /** 作業文脈の引き継ぎにおける起点の選択肢。 */
  public enum Origin {
    /** 利用者。 */
    USER,
    /** ツール。 */
    TOOL,
    /** アシスタント。 */
    ASSISTANT,
    /** 推定。 */
    INFERENCE
  }

  /**
   * 作業文脈の引き継ぎで受け渡すGit状態を表す。
   *
   * @param directory ディレクトリ
   * @param branch ブランチ
   * @param commit 確定
   * @param capturedAt 観測日時
   */
  public record GitState(String directory, String branch, String commit, Instant capturedAt) {}

  /**
   * 作業文脈の引き継ぎで受け渡す根拠を表す。
   *
   * @param id 識別子
   * @param origin 起点
   * @param sessionId 会話の識別子
   * @param turnId やり取りの識別子
   * @param runId 実行の識別子
   * @param toolCallId ツール呼び出し識別子
   * @param filePath ファイルパス
   * @param commit 確定
   * @param observedAt 観測日時
   * @param acquiredAt 確保済み日時
   * @param text テキスト
   */
  public record Evidence(
      String id,
      Origin origin,
      String sessionId,
      String turnId,
      String runId,
      String toolCallId,
      String filePath,
      String commit,
      Instant observedAt,
      Instant acquiredAt,
      String text) {}

  /**
   * 作業文脈の引き継ぎで受け渡す項目を表す。
   *
   * @param id 識別子
   * @param kind 種類
   * @param text テキスト
   * @param reason 理由
   * @param status 状態
   * @param evidence 根拠
   * @param createdAt 作成日時
   * @param updatedAt 更新日時
   * @param userCorrected 利用者補正済み
   * @param supersededBy 置き換え済みの内容別
   * @param certainty 確かさ
   */
  public record Item(
      String id,
      Kind kind,
      String text,
      String reason,
      Status status,
      List<Evidence> evidence,
      Instant createdAt,
      Instant updatedAt,
      boolean userCorrected,
      String supersededBy,
      Origin certainty) {
    /** 受け取った情報からItemを作成する。 */
    public Item {
      evidence = List.copyOf(evidence);
      if (certainty == null) certainty = Origin.INFERENCE;
    }

    /** 作業文脈の引き継ぎを利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Item(
        String id,
        Kind kind,
        String text,
        String reason,
        Status status,
        List<Evidence> evidence,
        Instant createdAt,
        Instant updatedAt,
        boolean userCorrected,
        String supersededBy) {
      this(
          id,
          kind,
          text,
          reason,
          status,
          evidence,
          createdAt,
          updatedAt,
          userCorrected,
          supersededBy,
          evidence.stream()
              .map(Evidence::origin)
              .min(java.util.Comparator.naturalOrder())
              .orElse(Origin.INFERENCE));
    }
  }
}
