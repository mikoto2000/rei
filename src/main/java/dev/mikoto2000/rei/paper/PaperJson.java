package dev.mikoto2000.rei.paper;

final class PaperJson {
  static final tools.jackson.databind.json.JsonMapper MAPPER =
      tools.jackson.databind.json.JsonMapper.builder().build();

  static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  static <T> T read(String json, Class<T> type) {
    return MAPPER.readValue(json, type);
  }
}
