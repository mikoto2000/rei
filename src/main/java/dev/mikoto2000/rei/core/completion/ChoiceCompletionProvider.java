package dev.mikoto2000.rei.core.completion;

import java.util.List;

/** 選択入力補完を用途や設定に応じて提供する窓口。 */
public final class ChoiceCompletionProvider implements CompletionProvider {
  /** 入力候補の提示において、選択入力補完提供元への対応可否を返す。 */
  @Override
  public boolean supports(CompletionContext context) {
    return context.types().contains("choices");
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  @Override
  public List<CompletionCandidate> complete(CompletionContext context) {
    return context.choices().stream()
        .filter(c -> c.value().startsWith(context.currentToken()))
        .toList();
  }
}
