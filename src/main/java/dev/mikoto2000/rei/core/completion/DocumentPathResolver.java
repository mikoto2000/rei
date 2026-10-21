package dev.mikoto2000.rei.core.completion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文書パスの指定を解決し、利用する対象を確定する。
 *
 * <p>Shared explicit document glob expansion; callers select their own local filesystem base.
 */
public final class DocumentPathResolver {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private DocumentPathResolver() {}

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  public static List<String> resolve(List<String> documents, Path baseDirectory) {
    var base = baseDirectory.toAbsolutePath().normalize();
    return documents.stream()
        .flatMap(document -> expand(document, base).stream())
        .distinct()
        .toList();
  }

  /** 入力候補の提示で用いる展開を返す。 */
  private static List<String> expand(String document, Path base) {
    if (document.indexOf('*') < 0 && document.indexOf('?') < 0 && document.indexOf('[') < 0)
      return List.of(document);
    String pattern = document.startsWith("./") ? document.substring(2) : document;
    var matcher = base.getFileSystem().getPathMatcher("glob:" + pattern);
    try (var files = Files.walk(base)) {
      var matches =
          files
              .filter(Files::isRegularFile)
              .map(base::relativize)
              .filter(matcher::matches)
              .map(base::resolve)
              .map(Path::normalize)
              .map(Path::toString)
              .sorted()
              .toList();
      if (matches.isEmpty())
        throw new IllegalArgumentException("ワイルドカードに一致するファイルがありません: " + document);
      return matches;
    } catch (IOException error) {
      throw new IllegalStateException("ワイルドカードの展開に失敗しました: " + document, error);
    }
  }
}
