package dev.mikoto2000.rei.workcontext;

import dev.mikoto2000.rei.workcontext.WorkContext.Kind;
import dev.mikoto2000.rei.workcontext.WorkContext.Origin;
import dev.mikoto2000.rei.workcontext.WorkContext.Status;
import java.util.List;

/**
 * 作業文脈の引き継ぎで受け渡す作業文脈候補を表す。
 *
 * @param action 操作
 * @param targetId 対象識別子
 * @param kind 種類
 * @param text テキスト
 * @param reason 理由
 * @param status 状態
 * @param sourceIds 取得元識別子一覧
 * @param certainty 確かさ
 */
public record WorkContextCandidate(
    String action,
    String targetId,
    Kind kind,
    String text,
    String reason,
    Status status,
    List<String> sourceIds,
    Origin certainty) {
  /** 作業文脈の引き継ぎを利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WorkContextCandidate(
      String action,
      String targetId,
      Kind kind,
      String text,
      String reason,
      Status status,
      List<String> sourceIds) {
    this(action, targetId, kind, text, reason, status, sourceIds, null);
  }

  /** 受け取った情報からWorkContextCandidateを作成する。 */
  public WorkContextCandidate {
    if (!List.of("ADD", "CORRECT", "STATUS", "SUPERSEDE", "CONFLICT").contains(action)
        || kind == null
        || status == null
        || text == null
        || text.isBlank()
        || text.length() > 2000
        || reason == null
        || reason.length() > 2000
        || sourceIds == null
        || sourceIds.isEmpty()
        || sourceIds.size() > 20)
      throw new IllegalArgumentException("Invalid Work Context candidate");
    sourceIds = List.copyOf(sourceIds);
  }
}
