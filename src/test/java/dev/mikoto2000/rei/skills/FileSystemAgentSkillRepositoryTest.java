package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ファイルシステムエージェントスキル保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class FileSystemAgentSkillRepositoryTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void loadsSkillMdFromConfiguredDirectory() throws Exception {
    Path skillsDir = tempDir.resolve(".rei").resolve("skills");
    writeSkill(
        skillsDir.resolve("sample"),
        """
        ---
        name: sample
        description: Sample skill
        enabled: true
        ---

        # Instructions

        Do the sample work.
        """);
    AgentSkillsProperties properties = properties(skillsDir);
    FileSystemAgentSkillRepository repository = new FileSystemAgentSkillRepository(properties);

    List<AgentSkill> skills = repository.findAll();

    assertThat(skills).hasSize(1);
    AgentSkill skill = skills.get(0);
    assertThat(skill.name()).isEqualTo("sample");
    assertThat(skill.description()).isEqualTo("Sample skill");
    assertThat(skill.enabled()).isTrue();
    assertThat(skill.instructions()).contains("Do the sample work.");
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void excludesDisabledSkillsFromEnabledList() throws Exception {
    Path skillsDir = tempDir.resolve(".rei").resolve("skills");
    writeSkill(
        skillsDir.resolve("enabled"),
        """
        ---
        name: enabled
        description: Enabled skill
        enabled: true
        ---
        enabled instructions
        """);
    writeSkill(
        skillsDir.resolve("disabled"),
        """
        ---
        name: disabled
        description: Disabled skill
        enabled: false
        ---
        disabled instructions
        """);
    FileSystemAgentSkillRepository repository =
        new FileSystemAgentSkillRepository(properties(skillsDir));

    assertThat(repository.findAll())
        .extracting(AgentSkill::name)
        .containsExactlyInAnyOrder("enabled", "disabled");
    assertThat(repository.findEnabled()).extracting(AgentSkill::name).containsExactly("enabled");
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void loadsSkillMdWithFoldedDescription() throws Exception {
    Path skillsDir = tempDir.resolve(".rei").resolve("skills");
    writeSkill(
        skillsDir.resolve("powershell"),
        """
        ---
        name: powershell
        description: >
          Use this skill when working in PowerShell on Windows or cross-platform PowerShell.
          It provides guidance for correct PowerShell syntax.
        enabled: true
        ---

        Use PowerShell according to PowerShell semantics.
        """);
    FileSystemAgentSkillRepository repository =
        new FileSystemAgentSkillRepository(properties(skillsDir));

    List<AgentSkill> skills = repository.findAll();

    assertThat(skills).hasSize(1);
    assertThat(skills.get(0).name()).isEqualTo("powershell");
    assertThat(skills.get(0).description())
        .isEqualTo(
            "Use this skill when working in PowerShell on Windows or cross-platform PowerShell. It provides guidance for correct PowerShell syntax.");
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void loadsOptionalKeywordsFromFrontMatter() throws Exception {
    Path skillsDir = tempDir.resolve(".rei").resolve("skills");
    writeSkill(
        skillsDir.resolve("powershell"),
        """
        ---
        name: powershell
        description: PowerShell operations
        keywords:
          - invoke-webrequest
          - pwsh
          - "PowerShell"
        ---
        instructions
        """);

    AgentSkill skill =
        new FileSystemAgentSkillRepository(properties(skillsDir)).findAll().getFirst();

    assertThat(skill.keywords()).containsExactly("invoke-webrequest", "pwsh", "PowerShell");
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void defaultsKeywordsToEmptyList() throws Exception {
    Path skillsDir = tempDir.resolve(".rei").resolve("skills");
    writeSkill(
        skillsDir.resolve("sample"),
        """
        ---
        name: sample
        description: Sample
        ---
        instructions
        """);

    AgentSkill skill =
        new FileSystemAgentSkillRepository(properties(skillsDir)).findAll().getFirst();

    assertThat(skill.keywords()).isEmpty();
  }

  /** その他スキル一覧を維持することを確認する。 */
  @Test
  void skipsBrokenSkillButKeepsOtherSkills() throws Exception {
    Path skillsDir = tempDir.resolve(".rei").resolve("skills");
    writeSkill(
        skillsDir.resolve("valid"),
        """
        ---
        name: valid
        description: Valid skill
        ---
        valid instructions
        """);
    writeSkill(
        skillsDir.resolve("broken"),
        """
        ---
        name: broken
        description: Broken skill
        broken
        """);
    FileSystemAgentSkillRepository repository =
        new FileSystemAgentSkillRepository(properties(skillsDir));

    assertThat(repository.findAll()).extracting(AgentSkill::name).containsExactly("valid");
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void ignoresKiroDirectoryEvenWhenConfigured() throws Exception {
    Path kiroDir = tempDir.resolve(".kiro");
    writeSkill(
        kiroDir.resolve("sample"),
        """
        ---
        name: sample
        description: Should be ignored
        ---
        instructions
        """);
    FileSystemAgentSkillRepository repository =
        new FileSystemAgentSkillRepository(properties(kiroDir));

    assertThat(repository.findAll()).isEmpty();
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void reloadReflectsFileChanges() throws Exception {
    Path skillsDir = tempDir.resolve(".rei").resolve("skills");
    writeSkill(
        skillsDir.resolve("before"),
        """
        ---
        name: before
        description: Before skill
        ---
        before instructions
        """);
    FileSystemAgentSkillRepository repository =
        new FileSystemAgentSkillRepository(properties(skillsDir));
    assertThat(repository.findAll()).extracting(AgentSkill::name).containsExactly("before");

    writeSkill(
        skillsDir.resolve("after"),
        """
        ---
        name: after
        description: After skill
        ---
        after instructions
        """);
    repository.reload();

    assertThat(repository.findAll())
        .extracting(AgentSkill::name)
        .containsExactlyInAnyOrder("before", "after");
  }

  /** {@code properties} の条件を再現するための補助情報を返す。 */
  private AgentSkillsProperties properties(Path skillsDir) {
    AgentSkillsProperties properties = new AgentSkillsProperties();
    properties.setDirectories(List.of(skillsDir.toString()));
    return properties;
  }

  /** 検証用に、指定された内容を保存先へ反映するため、スキルを書き込む。 */
  private void writeSkill(Path skillDir, String content) throws Exception {
    Files.createDirectories(skillDir);
    Files.writeString(skillDir.resolve("SKILL.md"), content);
  }
}
