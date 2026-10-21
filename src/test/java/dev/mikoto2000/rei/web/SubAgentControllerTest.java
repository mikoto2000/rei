package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.subagent.SubAgentDefinitionLoader;
import dev.mikoto2000.rei.subagent.SubAgentRegistry;
import dev.mikoto2000.rei.subagent.SubAgentToolPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 補助エージェント受付窓口の結果と状態の契約を検証するテスト。 */
class SubAgentControllerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 設定済みディレクトリを利用することを確認する。 */
  @Test
  void managementUsesConfiguredDirectoryAndDoesNotExposePromptsOrOverwriteDefinitions()
      throws Exception {
    var policy = new SubAgentToolPolicy(Set.of());
    var loader = new SubAgentDefinitionLoader(policy, model -> true);
    var registry = new SubAgentRegistry(directory, loader);
    var controller = new SubAgentController(registry, loader, policy);
    assertEquals("SubAgents: none" + System.lineSeparator(), controller.list().text());
    assertTrue(
        controller
            .initialize(new SubAgentController.Identity("reviewer"))
            .text()
            .contains("Created:"));
    var content = Files.readString(directory.resolve("reviewer.yaml"));
    assertTrue(
        controller
            .initialize(new SubAgentController.Identity("reviewer"))
            .text()
            .contains("[error]"));
    assertEquals(content, Files.readString(directory.resolve("reviewer.yaml")));
    assertTrue(
        controller
            .validate(
                new SubAgentController.FileRequest(directory.resolve("reviewer.yaml").toString()))
            .text()
            .contains("valid: reviewer"));
    assertTrue(controller.reload().text().contains("SubAgents reloaded: 1"));
    assertTrue(controller.show("reviewer").text().contains("effective tools:"));
    assertFalse(controller.show("reviewer").text().contains("systemPrompt"));
    assertTrue(
        controller
            .initialize(new SubAgentController.Identity("../outside"))
            .text()
            .contains("[error]"));
    assertFalse(Files.exists(directory.getParent().resolve("outside.yaml")));
  }
}
