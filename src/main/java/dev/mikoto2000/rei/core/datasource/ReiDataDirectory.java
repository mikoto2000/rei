package dev.mikoto2000.rei.core.datasource;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** れいが保存するデータの基準ディレクトリを、利用環境と設定から決定する。 */
public final class ReiDataDirectory {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ReiDataDirectory() {}

  /** データ保存先の利用で用いる現在を返す。 */
  public static Path current() {
    String override = System.getProperty("rei.data-dir");
    return override != null && !override.isBlank()
        ? Path.of(override).toAbsolutePath().normalize()
        : resolve(
            System.getProperty("os.name"),
            Path.of(System.getProperty("user.home")),
            System.getenv());
  }

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  public static Path resolve(String os, Path home, Map<String, String> env) {
    Path path;
    if (present(env, "REI_DATA_DIR")) path = Path.of(env.get("REI_DATA_DIR"));
    else if (os.toLowerCase(Locale.ROOT).contains("win")) {
      path =
          (present(env, "LOCALAPPDATA")
                  ? Path.of(env.get("LOCALAPPDATA"))
                  : home.resolve("AppData/Local"))
              .resolve("Rei");
    } else if (os.toLowerCase(Locale.ROOT).contains("mac"))
      path = home.resolve("Library/Application Support/Rei");
    else
      path =
          (present(env, "XDG_DATA_HOME")
                  ? Path.of(env.get("XDG_DATA_HOME"))
                  : home.resolve(".local/share"))
              .resolve("rei");
    return path.toAbsolutePath().normalize();
  }

  /** データ保存先の利用において、存在するに該当するか判定する。 */
  private static boolean present(Map<String, String> env, String key) {
    return env.get(key) != null && !env.get(key).isBlank();
  }
}
