package dev.mikoto2000.rei.core.completion;

import java.util.Set;

/** パス入力補完で利用できる入力候補を提供する。 */
public final class PathCompletionCandidates {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private PathCompletionCandidates() {}

  /** 入力候補の提示に必要なファイルを扱う。 */
  public static class File implements CompletionMetadata {
    /** 入力候補の提示で用いる種別一覧を返す。 */
    public Set<String> types(CompletionContext context) {
      return Set.of(FilePathCompletionProvider.FILE);
    }
  }

  /** 入力候補の提示に必要なディレクトリを扱う。 */
  public static class Directory implements CompletionMetadata {
    /** 入力候補の提示で用いる種別一覧を返す。 */
    public Set<String> types(CompletionContext context) {
      return Set.of(FilePathCompletionProvider.DIRECTORY);
    }
  }

  /** 入力候補の提示に必要ないずれかを扱う。 */
  public static class Any implements CompletionMetadata {
    /** 入力候補の提示で用いる種別一覧を返す。 */
    public Set<String> types(CompletionContext context) {
      return Set.of(FilePathCompletionProvider.BOTH);
    }
  }
}
