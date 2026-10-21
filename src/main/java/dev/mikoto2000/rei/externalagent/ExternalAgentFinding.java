package dev.mikoto2000.rei.externalagent;

/**
 * 外部エージェントへの委譲で受け渡す外部エージェント指摘を表す。
 *
 * @param severity 重大度
 * @param title タイトル
 * @param reason 理由
 * @param recommendation 提案
 * @param location 場所
 */
public record ExternalAgentFinding(
    Severity severity, String title, String reason, String recommendation, String location) {
  /** 外部エージェントへの委譲における重大度の選択肢。 */
  public enum Severity {
    /** 重大な。 */
    critical,
    /** 高い重大度。 */
    high,
    /** 中程度の重大度。 */
    medium,
    /** 低い評価の区分。 */
    low,
    /** 付随情報。 */
    info
  }
}
