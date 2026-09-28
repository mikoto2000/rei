package dev.mikoto2000.rei.paper;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PaperSessionReferences {
  private final Map<String, List<String>> refs =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, List<String>> e) {
              return size() > 1000;
            }
          });

  public void replace(String session, List<String> ids) {
    if (session != null) refs.put(session, List.copyOf(ids));
  }

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
