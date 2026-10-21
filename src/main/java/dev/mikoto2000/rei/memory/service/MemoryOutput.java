package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.subagent.SubAgentResultParser;
import dev.mikoto2000.rei.subagent.SubAgentResultSchema;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 長期記憶の保存と検索に必要な記憶出力を扱う。
 *
 * <p>Reuses SubAgent strict parsing, bounded schema loading and offline Draft 2020-12 validation.
 */
public final class MemoryOutput {
  /** 候補一覧。 */
  private static final SubAgentResultSchema CANDIDATES = load("memory-candidates");

  /** 解決結果。 */
  private static final SubAgentResultSchema RESOLUTION = load("memory-resolution");

  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  private static SubAgentResultSchema load(String name) {
    return SubAgentResultSchema.load(
        java.nio.file.Path.of("."), "classpath:/subagents/schemas/" + name + ".schema.json");
  }

  /** 長期記憶の保存と検索で用いる候補データ形式を返す。 */
  public String candidateSchema() {
    return CANDIDATES.json();
  }

  /** 長期記憶の保存と検索で用いる解決結果データ形式を返す。 */
  public String resolutionSchema() {
    return RESOLUTION.json();
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private JsonNode validate(String text, SubAgentResultSchema schema) {
    var json = new SubAgentResultParser().parse(text);
    if (!schema.validate(json).isEmpty())
      throw new IllegalArgumentException("Memory output violates JSON schema");
    return json;
  }

  /** 長期記憶の保存と検索で用いる候補一覧を返す。 */
  public List<MemoryCandidate> candidates(String text) {
    var result = new ArrayList<MemoryCandidate>();
    for (var row : validate(text, CANDIDATES).get("memories"))
      result.add(
          new MemoryCandidate(
              MemoryType.valueOf(row.get("type").asString()),
              MemoryScope.valueOf(row.get("scope").asString()),
              row.get("content").asString(),
              row.get("summary").asString(),
              row.get("confidence").asDouble(),
              row.get("importance").asDouble(),
              strings(row.get("sourceTurnIds")),
              strings(row.get("tags"))));
    return List.copyOf(result);
  }

  /** 長期記憶の保存と検索で用いる解決結果を返す。 */
  public MemoryResolution resolution(String text) {
    var row = validate(text, RESOLUTION);
    return new MemoryResolution(
        MemoryAction.valueOf(row.get("action").asString()), strings(row.get("targetIds")));
  }

  /** 長期記憶の保存と検索で用いる文字列一覧を返す。 */
  private List<String> strings(JsonNode array) {
    var result = new ArrayList<String>();
    for (var item : array) result.add(item.asString());
    return List.copyOf(result);
  }
}
