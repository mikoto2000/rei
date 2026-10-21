package dev.mikoto2000.rei.skills;

import java.nio.file.Path;
import java.util.List;

/**
 * スキルの利用で受け渡すエージェントスキルを表す。
 *
 * @param name 名前
 * @param description 説明
 * @param keywords 検索語句一覧
 * @param enabled 有効状態
 * @param directory ディレクトリ
 * @param skillFile スキルファイル
 * @param instructions 指示一覧
 */
public record AgentSkill(
    String name,
    String description,
    List<String> keywords,
    boolean enabled,
    Path directory,
    Path skillFile,
    String instructions) {

  /** 受け取った情報からAgentSkillを作成する。 */
  public AgentSkill {
    keywords = keywords == null ? List.of() : List.copyOf(keywords);
  }

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentSkill(
      String name,
      String description,
      boolean enabled,
      Path directory,
      Path skillFile,
      String instructions) {
    this(name, description, List.of(), enabled, directory, skillFile, instructions);
  }
}
