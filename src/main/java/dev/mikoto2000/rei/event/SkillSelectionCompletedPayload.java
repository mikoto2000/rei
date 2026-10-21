package dev.mikoto2000.rei.event;

import java.util.List;

/**
 * Agent Skill の選定結果。
 *
 * @param selectionId 選択内容識別子
 * @param explicitSkillNames 明示的なスキル名前一覧
 * @param implicitSkillNames 暗黙のスキル名前一覧
 * @param warnings 注意事項
 */
public record SkillSelectionCompletedPayload(
    String selectionId,
    List<String> explicitSkillNames,
    List<String> implicitSkillNames,
    List<String> warnings)
    implements AgentEventPayload {

  /** 受け取った情報からSkillSelectionCompletedPayloadを作成する。 */
  public SkillSelectionCompletedPayload {
    explicitSkillNames = explicitSkillNames == null ? List.of() : List.copyOf(explicitSkillNames);
    implicitSkillNames = implicitSkillNames == null ? List.of() : List.copyOf(implicitSkillNames);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
