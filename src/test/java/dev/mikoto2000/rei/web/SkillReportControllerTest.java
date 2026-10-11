package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.skills.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
class SkillReportControllerTest {
  @Test void reportContainsTheSameFieldsAndMissingNameMessageAsShell() {
    var repo=mock(AgentSkillRepository.class);var skill=new AgentSkill("sample","Description",true,Path.of("skills/sample"),Path.of("skills/sample/SKILL.md"),"Instructions");
    when(repo.findAll()).thenReturn(List.of(skill));when(repo.findByName("sample")).thenReturn(Optional.of(skill));when(repo.findByName("missing")).thenReturn(Optional.empty());
    var controller=new SkillReportController(repo);
    assertTrue(controller.list().text().contains("sample | enabled | Description | skills"));
    assertTrue(controller.show("sample").text().contains("skillFile: "+skill.skillFile()));
    assertTrue(controller.show("sample").text().endsWith("Instructions"+System.lineSeparator()));
    assertEquals("[error] Skill が見つかりません: missing"+System.lineSeparator(),controller.show("missing").text());
    assertEquals("Agent Skills を再読み込みしました: 1 件"+System.lineSeparator(),controller.reload().text());verify(repo).reload();
  }
}
