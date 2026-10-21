package dev.mikoto2000.rei.core.completion;

import java.util.List;

/**
 * 入力補完を用途や設定に応じて提供する窓口。
 *
 * <p>Implementations must be bounded, read-only and must never execute tools or contact an LLM.
 */
public interface CompletionProvider {
  /** 入力候補の提示で用いる優先度を返す。 */
  default int priority() {
    return 100;
  }

  /** 入力候補の提示において、入力補完提供元への対応可否を返す。 */
  boolean supports(CompletionContext context);

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  List<CompletionCandidate> complete(CompletionContext context);
}
