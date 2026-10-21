package dev.mikoto2000.rei.core.completion;

import java.util.List;
import java.util.Set;

/**
 * パス代替手段入力補完を用途や設定に応じて提供する窓口。
 *
 * <p>Ordinary prose is not a path request. Explicit path prefixes opt into fallback.
 */
public final class PathFallbackCompletionProvider implements CompletionProvider {
  /** パス一覧。 */
  private final FilePathCompletionProvider paths = new FilePathCompletionProvider();

  /** 入力候補の提示で用いる優先度を返す。 */
  @Override
  public int priority() {
    return 0;
  }

  /** 入力候補の提示において、パス代替手段入力補完提供元への対応可否を返す。 */
  @Override
  public boolean supports(CompletionContext context) {
    String word = context.currentToken();
    return context.types().isEmpty()
        && (word.startsWith("./")
            || word.startsWith("../")
            || word.startsWith("~/")
            || word.startsWith("/")
            || word.startsWith(".\\")
            || word.startsWith("..\\")
            || word.startsWith("~\\")
            || word.matches("^[a-zA-Z]:[\\\\/].*")
            || word.startsWith("\\\\"));
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  @Override
  public List<CompletionCandidate> complete(CompletionContext context) {
    return paths.complete(context.withTypes(Set.of(FilePathCompletionProvider.BOTH)));
  }
}
