package dev.mikoto2000.rei.paper;

import java.util.List;
import java.util.Map;

/**
 * 論文の調査と整理で受け渡す論文翻訳を表す。
 *
 * @param paperId 論文識別子
 * @param mode 動作モード
 * @param model モデル
 * @param promptVersion プロンプト形式のバージョン
 * @param glossaryVersion 用語集形式のバージョン
 * @param createdAt 作成日時
 * @param section 節
 * @param availability 利用可否
 * @param glossary 用語集
 * @param chunks 文書の分割一覧
 * @param warnings 注意事項
 */
public record PaperTranslation(
    String paperId,
    Mode mode,
    String model,
    String promptVersion,
    String glossaryVersion,
    String createdAt,
    String section,
    StructuredPaper.Availability availability,
    Map<String, String> glossary,
    List<Chunk> chunks,
    List<String> warnings) {
  /** 指定された論文識別子を反映した情報を作成する。 */
  public PaperTranslation withPaperId(String id) {
    return new PaperTranslation(
        id,
        mode,
        model,
        promptVersion,
        glossaryVersion,
        createdAt,
        section,
        availability,
        glossary,
        chunks,
        warnings);
  }

  /** 論文の調査と整理における動作モードの選択肢。 */
  public enum Mode {
    /** そのままの値。 */
    LITERAL,
    /** 自然な文章での翻訳指定。 */
    NATURAL,
    /** 技術用語を重視する翻訳指定。 */
    TECHNICAL
  }

  /**
   * 論文の調査と整理で受け渡す文書の分割単位を表す。
   *
   * @param section 節
   * @param page ページ
   * @param index 位置
   * @param content 内容
   */
  public record Chunk(String section, int page, int index, String content) {}
}
