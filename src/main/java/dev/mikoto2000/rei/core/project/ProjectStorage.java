package dev.mikoto2000.rei.core.project;

import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import java.nio.file.Path;

/** プロジェクトごとの作業環境に必要なプロジェクト保存を扱う。 */
public final class ProjectStorage {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ProjectStorage() {}

  /** プロジェクトごとの作業環境で用いるディレクトリを返す。 */
  public static Path directory(String projectId) {
    java.util.UUID.fromString(projectId);
    return ReiDataDirectory.current().resolve("projects").resolve(projectId);
  }

  /** プロジェクトごとの作業環境で用いる所属プロジェクトの識別子を返す。 */
  public static String projectId(String conversationId) {
    if (conversationId != null && conversationId.startsWith("project:")) {
      int end = conversationId.indexOf(':', 8);
      if (end > 8) {
        String id = conversationId.substring(8, end);
        java.util.UUID.fromString(id);
        return id;
      }
    }
    return null;
  }

  /** プロジェクトごとの作業環境で用いる現在ディレクトリを返す。 */
  public static Path currentDirectory() {
    var context = ProjectService.contextForOperation();
    return context == null ? ReiDataDirectory.current() : directory(context.id());
  }
}
