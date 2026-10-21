package dev.mikoto2000.rei.paper;

/** 論文の調査と整理に必要な論文JSON変換を扱う。 */
final class PaperJson {
  /** データの変換。 */
  static final tools.jackson.databind.json.JsonMapper MAPPER =
      tools.jackson.databind.json.JsonMapper.builder().build();

  /** 指定された内容を書き込み先へ反映する。 */
  static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  static <T> T read(String json, Class<T> type) {
    return MAPPER.readValue(json, type);
  }
}
