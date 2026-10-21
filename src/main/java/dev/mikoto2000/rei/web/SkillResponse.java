package dev.mikoto2000.rei.web;

/**
 * Web APIによる操作で受け渡すスキル応答を表す。
 *
 * @param name 名前
 * @param description 説明
 * @param enabled 有効状態
 * @param instructions 指示一覧
 */
public record SkillResponse(String name, String description, boolean enabled, String instructions) {
  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  public static SkillResponse from(dev.mikoto2000.rei.skills.AgentSkill s) {
    return new SkillResponse(s.name(), s.description(), s.enabled(), s.instructions());
  }
}
