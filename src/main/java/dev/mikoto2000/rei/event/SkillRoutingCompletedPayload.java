package dev.mikoto2000.rei.event;

import java.util.List;

/**
 * Skill routing の正常終了と内訳。duration はすべて monotonic clock によるミリ秒値。
 *
 * @param durationMs 継続時間ミリ秒
 * @param candidateCount 候補件数
 * @param selectedSkill 選択中スキル
 * @param routingInvocation 振り分けInvocation
 * @param selectorDurationMs 対象の選択条件継続時間ミリ秒
 * @param metadataLoadDurationMs 付随情報読み込み継続時間ミリ秒
 * @param skillLoadDurationMs スキル読み込み継続時間ミリ秒
 * @param explicitSkillNames 明示的なスキル名前一覧
 * @param implicitSkillNames 暗黙のスキル名前一覧
 * @param warnings 注意事項
 */
public record SkillRoutingCompletedPayload(
    long durationMs,
    int candidateCount,
    String selectedSkill,
    int routingInvocation,
    Long selectorDurationMs,
    Long metadataLoadDurationMs,
    Long skillLoadDurationMs,
    List<String> explicitSkillNames,
    List<String> implicitSkillNames,
    List<String> warnings)
    implements AgentEventPayload {

  /** 受け取った情報からSkillRoutingCompletedPayloadを作成する。 */
  public SkillRoutingCompletedPayload {
    explicitSkillNames = explicitSkillNames == null ? List.of() : List.copyOf(explicitSkillNames);
    implicitSkillNames = implicitSkillNames == null ? List.of() : List.copyOf(implicitSkillNames);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
