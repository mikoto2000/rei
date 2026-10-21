package dev.mikoto2000.rei.core.project;

import java.nio.file.Path;

/**
 * プロジェクトごとの作業環境で受け渡すプロジェクト文脈を表す。
 *
 * <p>Persistent identity is independent of its mutable filesystem location.
 *
 * @param id 識別子
 * @param name 名前
 * @param root 作業ルート
 */
public record ProjectContext(String id, String name, Path root) {
  /** 受け取った情報からProjectContextを作成する。 */
  public ProjectContext {
    java.util.UUID.fromString(id);
    root = root.toAbsolutePath().normalize();
  }

  /** プロジェクトごとの作業環境で用いる会話の識別子を返す。 */
  public String conversationId(String logicalId) {
    return "project:" + id + ":" + logicalId;
  }
}
