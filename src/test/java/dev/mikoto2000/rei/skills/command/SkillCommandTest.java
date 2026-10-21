package dev.mikoto2000.rei.skills.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.skills.AgentSkill;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine;

/** スキルコマンドの結果と状態の契約を検証するテスト。 */
class SkillCommandTest {

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void listPrintsSkills() {
    AgentSkillRepository repository = repository(List.of(skill("sample", true)));
    String output = execute(repository, "list");

    assertThat(output).contains("sample");
    assertThat(output).contains("enabled");
    assertThat(output).contains("sample description");
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void listPrintsEmptyMessageWhenNoSkillsExist() {
    String output = execute(repository(List.of()), "list");

    assertThat(output).contains("Agent Skills は登録されていません");
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void showPrintsSkillDetails() {
    String output = execute(repository(List.of(skill("sample", true))), "show", "sample");

    assertThat(output).contains("name: sample");
    assertThat(output).contains("enabled: true");
    assertThat(output).contains("sample instructions");
  }

  /** スキルの利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void showPrintsErrorWhenSkillDoesNotExist() {
    String output = execute(repository(List.of()), "show", "missing");

    assertThat(output).contains("[error] Skill が見つかりません: missing");
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void reloadReloadsRepository() {
    AgentSkillRepository repository = Mockito.mock(AgentSkillRepository.class);
    Mockito.when(repository.findAll()).thenReturn(List.of(skill("sample", true)));

    String output = execute(repository, "reload");

    verify(repository).reload();
    assertThat(output).contains("Agent Skills を再読み込みしました: 1 件");
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void repositoryConstructorIsAutowired() throws Exception {
    assertThat(
            SkillCommand.class
                .getConstructor(AgentSkillRepository.class)
                .isAnnotationPresent(Autowired.class))
        .isTrue();
  }

  /** 検証用に、要求された処理を実行する。 */
  private String execute(AgentSkillRepository repository, String... args) {
    StringWriter writer = new StringWriter();
    CommandLine commandLine = new CommandLine(new SkillCommand(repository));
    commandLine.setOut(new PrintWriter(writer, true));
    commandLine.setErr(new PrintWriter(writer, true));
    commandLine.execute(args);
    return writer.toString();
  }

  /** 検証で使用する保存と検索の窓口を、指定された条件に合わせて用意する。 */
  private AgentSkillRepository repository(List<AgentSkill> skills) {
    return new AgentSkillRepository() {
      /** 検証用に、対象を特定できるよう、全対象を探す。 */
      @Override
      public List<AgentSkill> findAll() {
        return skills;
      }

      /** 検証用に、対象を特定できるよう、有効状態を探す。 */
      @Override
      public List<AgentSkill> findEnabled() {
        return skills.stream().filter(AgentSkill::enabled).toList();
      }

      /** 検証用に、対象を特定できるよう、別名前を探す。 */
      @Override
      public Optional<AgentSkill> findByName(String name) {
        return skills.stream().filter(skill -> skill.name().equals(name)).findFirst();
      }

      /** {@code reload} の条件を再現するため、検証用の操作や状態を扱う。 */
      @Override
      public void reload() {}
    };
  }

  /** {@code skill} の条件を再現するための補助情報を返す。 */
  private AgentSkill skill(String name, boolean enabled) {
    return new AgentSkill(
        name,
        name + " description",
        enabled,
        Path.of(name),
        Path.of(name).resolve("SKILL.md"),
        name + " instructions");
  }
}
