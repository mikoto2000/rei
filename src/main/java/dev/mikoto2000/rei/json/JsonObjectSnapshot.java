package dev.mikoto2000.rei.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JSONオブジェクトの項目順とnull値を保ち、入れ子のマップと配列を独立させる。 */
public final class JsonObjectSnapshot {
  private JsonObjectSnapshot() {}

  /** 読み取り専用の入れ子を含む、呼び出し元が所有する新しいマップを返す。 */
  public static Map<String, Object> copy(Map<String, Object> source) {
    return copyMap(source, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  private static <K> Map<K, Object> copyMap(Map<K, ?> source, Set<Object> active) {
    enter(source, active);
    var copy = new LinkedHashMap<K, Object>(source.size());
    source.forEach((key, value) -> copy.put(key, copyValue(value, active)));
    active.remove(source);
    return copy;
  }

  private static Object copyValue(Object value, Set<Object> active) {
    if (value instanceof Map<?, ?> map) return Collections.unmodifiableMap(copyMap(map, active));
    if (value instanceof List<?> list) {
      enter(list, active);
      var copy = new ArrayList<>(list.size());
      for (var item : list) copy.add(copyValue(item, active));
      active.remove(list);
      return Collections.unmodifiableList(copy);
    }
    return value;
  }

  private static void enter(Object value, Set<Object> active) {
    if (!active.add(value)) throw new IllegalArgumentException("Cyclic JSON container");
  }
}
