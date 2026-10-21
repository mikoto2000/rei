package dev.mikoto2000.rei.externalagent;

import java.util.List;

/**
 * 外部エージェントへの委譲で受け渡す実装仕様を表す。
 *
 * <p>Requirements are data; no field supplies authority or executable commands.
 *
 * @param schemaVersion データ形式形式のバージョン
 * @param objective 目的
 * @param instructions 指示一覧
 * @param target 対象
 * @param allowedPaths 許可パス一覧
 * @param constraints 制約一覧
 * @param acceptanceCriteria 受け入れ条件判断基準一覧
 * @param references 参照先一覧
 * @param changeMode 変更動作モード
 */
public record ImplementationSpecification(
    int schemaVersion,
    String objective,
    List<String> instructions,
    String target,
    List<String> allowedPaths,
    List<String> constraints,
    List<AcceptanceCriterion> acceptanceCriteria,
    List<RequirementReference> references,
    String changeMode) {
  /**
   * 外部エージェントへの委譲で受け渡す受け入れ条件判断基準を表す。
   *
   * @param id 識別子
   * @param description 説明
   */
  public record AcceptanceCriterion(String id, String description) {}

  /**
   * 外部エージェントへの委譲で受け渡す要件参照先を表す。
   *
   * @param sourceType 取得元種別
   * @param locator 対象の場所を示す情報
   * @param excerpt 根拠として示す抜粋
   */
  public record RequirementReference(String sourceType, String locator, String excerpt) {}
}
