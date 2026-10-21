package dev.mikoto2000.rei.paper;

import java.util.List;

/**
 * 論文の調査と整理で受け渡す論文要約を表す。
 *
 * @param paperId 論文識別子
 * @param mode 動作モード
 * @param model モデル
 * @param promptVersion プロンプト形式のバージョン
 * @param createdAt 作成日時
 * @param availability 利用可否
 * @param content 内容
 * @param evidence 根拠
 * @param warnings 注意事項
 */
public record PaperSummary(
    String paperId,
    Mode mode,
    String model,
    String promptVersion,
    String createdAt,
    StructuredPaper.Availability availability,
    Content content,
    List<PaperEvidence> evidence,
    List<String> warnings) {
  /** 指定された論文識別子を反映した情報を作成する。 */
  public PaperSummary withPaperId(String id) {
    return new PaperSummary(
        id, mode, model, promptVersion, createdAt, availability, content, evidence, warnings);
  }

  /** 論文の調査と整理における動作モードの選択肢。 */
  public enum Mode {
    /** 簡潔な要約の指定。 */
    QUICK,
    /** 標準。 */
    STANDARD,
    /** 詳細な要約の指定。 */
    DETAILED
  }

  /**
   * 論文の調査と整理で受け渡す内容を表す。
   *
   * @param problem 問題
   * @param background バックグラウンド
   * @param contributions 主な成果
   * @param method メソッド
   * @param datasets データセット
   * @param evaluation 評価
   * @param results 結果一覧
   * @param limitations 制約一覧
   * @param futureWork 完了待ち作業
   */
  public record Content(
      String problem,
      String background,
      List<String> contributions,
      String method,
      List<String> datasets,
      String evaluation,
      List<String> results,
      List<String> limitations,
      List<String> futureWork) {}
}
