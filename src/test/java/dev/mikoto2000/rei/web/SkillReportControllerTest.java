package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.skills.AgentSkill;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** スキルレポート受付窓口の結果と状態の契約を検証するテスト。 */
class SkillReportControllerTest {
  /** 含有同一項目一覧を伝えることを確認する。 */
  @Test
  void reportContainsTheSameFieldsAndMissingNameMessageAsShell() {
    var repo = mock(AgentSkillRepository.class);
    var skill =
        new AgentSkill(
            "sample",
            "Description",
            true,
            Path.of("skills/sample"),
            Path.of("skills/sample/SKILL.md"),
            "Instructions");
    when(repo.findAll()).thenReturn(List.of(skill));
    when(repo.findByName("sample")).thenReturn(Optional.of(skill));
    when(repo.findByName("missing")).thenReturn(Optional.empty());
    var controller = new SkillReportController(repo);
    assertTrue(controller.list().text().contains("sample | enabled | Description | skills"));
    assertTrue(controller.show("sample").text().contains("skillFile: " + skill.skillFile()));
    assertTrue(controller.show("sample").text().endsWith("Instructions" + System.lineSeparator()));
    assertEquals(
        "[error] Skill が見つかりません: missing" + System.lineSeparator(),
        controller.show("missing").text());
    assertEquals(
        "Agent Skills を再読み込みしました: 1 件" + System.lineSeparator(), controller.reload().text());
    verify(repo).reload();
  }
}
