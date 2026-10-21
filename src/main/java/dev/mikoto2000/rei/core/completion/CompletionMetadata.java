package dev.mikoto2000.rei.core.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 入力候補の提示に必要な入力補完付随情報を扱う。
 *
 * <p>Usable directly as picocli completionCandidates without coupling this API to picocli.
 */
public interface CompletionMetadata extends Iterable<String> {
  /** 入力候補の提示で用いる種別一覧を返す。 */
  Set<String> types(CompletionContext context);

  /** 入力候補の提示で用いる選択肢を返す。 */
  default List<CompletionCandidate> choices(CompletionContext context) {
    var values = new ArrayList<CompletionCandidate>();
    for (String value : this) values.add(CompletionCandidate.value(value, "value"));
    return values;
  }

  /** 利用可能な候補を順に参照するための窓口を返す。 */
  @Override
  default Iterator<String> iterator() {
    return Collections.emptyIterator();
  }
}
