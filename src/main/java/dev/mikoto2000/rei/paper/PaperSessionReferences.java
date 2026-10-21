package dev.mikoto2000.rei.paper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 論文の調査と整理に必要な論文会話参照先一覧を扱う。 */
@Component
public class PaperSessionReferences {
  /** 参照先一覧。 */
  private final Map<String, List<String>> refs =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            /** 対象を管理から外すため、Eldest項目を取り除く。 */
            protected boolean removeEldestEntry(Map.Entry<String, List<String>> e) {
              return size() > 1000;
            }
          });

  /** 指定された内容へ切り替えるため、論文会話参照先一覧を置き換える。 */
  public void replace(String session, List<String> ids) {
    if (session != null) refs.put(session, List.copyOf(ids));
  }

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  public String resolve(String session, String ref) {
    try {
      if (ref.matches("[0-9]+")) {
        var ids = session == null ? List.<String>of() : refs.getOrDefault(session, List.of());
        int n = Integer.parseInt(ref);
        if (n < 1 || n > ids.size()) throw new IllegalArgumentException();
        return ids.get(n - 1);
      }
      return UUID.fromString(ref).toString();
    } catch (RuntimeException e) {
      throw new PaperException(
          PaperException.Code.INVALID_PAPER_REFERENCE, "論文番号または内部 UUID を確認してください");
    }
  }
}
