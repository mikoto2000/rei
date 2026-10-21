package dev.mikoto2000.rei.core.sqlitevec;

import org.springframework.stereotype.Component;

/** 文書検索用の保存基盤に必要な実行環境検出の窓口を扱う。 */
@Component
public class PlatformDetector {

  /** 文書検索用の保存基盤で用いる検出を返す。 */
  public SqliteVecPlatform detect() {
    return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
  }

  /** 文書検索用の保存基盤で用いる検出を返す。 */
  SqliteVecPlatform detect(String osName, String osArch) {
    String normalizedOs = osName == null ? "" : osName.toLowerCase();
    String normalizedArch = normalizeArch(osArch);

    if (normalizedOs.contains("linux")) {
      return switch (normalizedArch) {
        case "x86_64" -> SqliteVecPlatform.LINUX_X86_64;
        case "aarch64" -> SqliteVecPlatform.LINUX_AARCH64;
        default -> throw unsupported(osName, osArch);
      };
    }
    if (normalizedOs.contains("mac")) {
      return switch (normalizedArch) {
        case "x86_64" -> SqliteVecPlatform.MACOS_X86_64;
        case "aarch64" -> SqliteVecPlatform.MACOS_AARCH64;
        default -> throw unsupported(osName, osArch);
      };
    }
    if (normalizedOs.contains("win")) {
      if ("x86_64".equals(normalizedArch)) {
        return SqliteVecPlatform.WINDOWS_X86_64;
      }
      throw unsupported(osName, osArch);
    }
    throw unsupported(osName, osArch);
  }

  /** 同じ意味の指定を共通に扱えるよう、Archの表記をそろえる。 */
  private String normalizeArch(String osArch) {
    if (osArch == null) {
      return "";
    }
    return switch (osArch.toLowerCase()) {
      case "amd64", "x86_64" -> "x86_64";
      case "arm64", "aarch64" -> "aarch64";
      default -> osArch.toLowerCase();
    };
  }

  /** 文書検索用の保存基盤で用いる未対応を返す。 */
  private IllegalStateException unsupported(String osName, String osArch) {
    return new IllegalStateException(
        "sqlite-vec 未対応の platform です: os=" + osName + ", arch=" + osArch);
  }
}
