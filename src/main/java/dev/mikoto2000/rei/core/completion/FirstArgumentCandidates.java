package dev.mikoto2000.rei.core.completion;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 最初引数で利用できる入力候補を提供する。
 *
 * <p>Fixed action choices for commands that parse their first positional argument themselves.
 */
public abstract class FirstArgumentCandidates implements CompletionMetadata {
  /** 値一覧。 */
  private final List<String> values;

  /** 指定された値や利用先を使って、FirstArgumentCandidatesを初期化する。 */
  protected FirstArgumentCandidates(String... values) {
    this.values = List.of(values);
  }

  /** 入力候補の提示で用いる種別一覧を返す。 */
  @Override
  public Set<String> types(CompletionContext context) {
    return context.argumentIndex() == 0 ? Set.of("choices") : Set.of();
  }

  /** 入力候補の提示で用いる選択肢を返す。 */
  @Override
  public List<CompletionCandidate> choices(CompletionContext context) {
    return context.argumentIndex() == 0 ? CompletionMetadata.super.choices(context) : List.of();
  }

  /** 利用可能な候補を順に参照するための窓口を返す。 */
  @Override
  public Iterator<String> iterator() {
    return values.iterator();
  }
}
