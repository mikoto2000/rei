package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** エージェントスキル選択内容サービスの結果と状態の契約を検証するテスト。 */
class AgentSkillSelectionServiceTest {

  /** スキルの利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void measuresImplicitSelectorWithMonotonicClock() {
    AgentSkillsProperties properties = new AgentSkillsProperties();
    AgentSkillExplicitSelector explicitSelector = Mockito.mock(AgentSkillExplicitSelector.class);
    AgentSkillImplicitSelection implicitSelector = Mockito.mock(AgentSkillImplicitSelection.class);
    when(explicitSelector.select("implicit"))
        .thenReturn(
            new AgentSkillExplicitSelector.ExplicitSelection(List.of(), List.of(), "implicit"));
    AgentSkill candidate = skill("implicit");
    when(implicitSelector.select("implicit", Set.of(), List.of(candidate)))
        .thenReturn(List.of(candidate));
    java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
    AgentSkillSelectionService service =
        new AgentSkillSelectionService(
            properties,
            explicitSelector,
            implicitSelector,
            new InMemoryAgentSkillRepository(List.of(candidate)),
            new SkillCandidateSelector(),
            () -> nanos.getAndAdd(23_000_000L));

    AgentSkillSelection selection = service.select("implicit");

    assertThat(selection.selectorDurationMs()).isEqualTo(23L);
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void passesOnlyLexicalTopFiveToImplicitSelector() {
    AgentSkillsProperties properties = new AgentSkillsProperties();
    AgentSkillExplicitSelector explicitSelector = Mockito.mock(AgentSkillExplicitSelector.class);
    when(explicitSelector.select("target request"))
        .thenReturn(
            new AgentSkillExplicitSelector.ExplicitSelection(
                List.of(), List.of(), "target request"));
    List<AgentSkill> allSkills =
        java.util.stream.IntStream.range(0, 8)
            .mapToObj(
                index ->
                    new AgentSkill(
                        "skill-" + index,
                        "target",
                        true,
                        Path.of("skill-" + index),
                        Path.of("skill-" + index, "SKILL.md"),
                        "instructions"))
            .toList();
    AgentSkillImplicitSelection implicitSelector = Mockito.mock(AgentSkillImplicitSelection.class);
    when(implicitSelector.select(
            Mockito.eq("target request"), Mockito.eq(Set.of()), Mockito.anyList()))
        .thenReturn(List.of());
    AgentSkillSelectionService service =
        new AgentSkillSelectionService(
            properties,
            explicitSelector,
            implicitSelector,
            new InMemoryAgentSkillRepository(allSkills),
            new SkillCandidateSelector(),
            System::nanoTime);

    AgentSkillSelection selection = service.select("target request");

    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<List<AgentSkill>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    Mockito.verify(implicitSelector)
        .select(Mockito.eq("target request"), Mockito.eq(Set.of()), captor.capture());
    assertThat(captor.getValue()).hasSize(5);
    assertThat(selection.candidateSkills()).isEqualTo(captor.getValue());
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void explicitSelectionTakesPrecedenceOverImplicitSelection() {
    AgentSkill explicit = skill("explicit");
    AgentSkill implicit = skill("implicit");
    AgentSkillsProperties properties = new AgentSkillsProperties();
    AgentSkillSelectionService service =
        new AgentSkillSelectionService(
            properties,
            new AgentSkillExplicitSelector(new InMemoryAgentSkillRepository(List.of(explicit))),
            (prompt, excludedSkillNames, candidates) -> List.of(implicit));

    AgentSkillSelection selection = service.select("@skill:explicit do it");

    assertThat(selection.selectedSkills()).containsExactly(explicit);
    assertThat(selection.sanitizedPrompt()).isEqualTo("do it");
    assertThat(selection.selectorDurationMs()).isNull();
  }

  @Test
  void explicitSelectionSkipsCandidateSearchSelectorAndBudgetReservation() {
    AgentSkill explicit = skill("explicit");
    var repository = Mockito.mock(AgentSkillRepository.class);
    var candidates = Mockito.mock(SkillCandidateSelector.class);
    var implicit = Mockito.mock(AgentSkillImplicitSelection.class);
    var reservation = Mockito.mock(Runnable.class);
    var service =
        new AgentSkillSelectionService(
            new AgentSkillsProperties(),
            new AgentSkillExplicitSelector(new InMemoryAgentSkillRepository(List.of(explicit))),
            implicit,
            repository,
            candidates,
            System::nanoTime);

    assertThat(service.select("@skill:explicit do it", reservation).selectedSkills())
        .containsExactly(explicit);

    Mockito.verifyNoInteractions(repository, candidates, implicit, reservation);
  }

  @Test
  void canOptIntoAdditionalImplicitSkillsWithExplicitSelection() {
    AgentSkill explicit = skill("explicit");
    AgentSkill implicit = skill("implicit");
    var properties = new AgentSkillsProperties();
    properties.setImplicitWithExplicit(true);
    var service =
        new AgentSkillSelectionService(
            properties,
            new AgentSkillExplicitSelector(new InMemoryAgentSkillRepository(List.of(explicit))),
            (prompt, excluded, candidates) -> List.of(implicit));

    assertThat(service.select("@skill:explicit do it").selectedSkills())
        .containsExactly(explicit, implicit);
  }

  @Test
  void emptyCandidatesSkipCustomSelectorAndBudgetReservation() {
    var repository = new InMemoryAgentSkillRepository(List.of(skill("unrelated")));
    var implicit = Mockito.mock(AgentSkillImplicitSelection.class);
    var reservation = Mockito.mock(Runnable.class);
    var service =
        new AgentSkillSelectionService(
            new AgentSkillsProperties(),
            new AgentSkillExplicitSelector(repository),
            implicit,
            repository,
            new SkillCandidateSelector(),
            System::nanoTime);

    assertThat(service.select("hello", reservation).selectedSkills()).isEmpty();
    Mockito.verifyNoInteractions(implicit, reservation);
  }

  /** 重複の有無を取り除くことを確認する。 */
  @Test
  void removesDuplicateAndAppliesMaxSelected() {
    AgentSkill first = skill("first");
    AgentSkill second = skill("second");
    AgentSkillsProperties properties = new AgentSkillsProperties();
    properties.setMaxSelected(1);
    AgentSkillSelectionService service =
        new AgentSkillSelectionService(
            properties,
            new AgentSkillExplicitSelector(new InMemoryAgentSkillRepository(List.of(first))),
            (prompt, excludedSkillNames, candidates) -> List.of(first, second));

    AgentSkillSelection selection = service.select("@skill:first do it");

    assertThat(selection.selectedSkills()).containsExactly(first);
  }

  /** なし選択内容を返すことを確認する。 */
  @Test
  void returnsNoSelectionWhenDisabled() {
    AgentSkillsProperties properties = new AgentSkillsProperties();
    properties.setEnabled(false);
    AgentSkillSelectionService service =
        new AgentSkillSelectionService(
            properties,
            new AgentSkillExplicitSelector(
                new InMemoryAgentSkillRepository(List.of(skill("sample")))),
            (prompt, excludedSkillNames, candidates) -> List.of(skill("implicit")));

    AgentSkillSelection selection = service.select("@skill:sample do it");

    assertThat(selection.selectedSkills()).isEmpty();
    assertThat(selection.sanitizedPrompt()).isEqualTo("@skill:sample do it");
  }

  /** {@code skill} の条件を再現するための補助情報を返す。 */
  private AgentSkill skill(String name) {
    return new AgentSkill(
        name,
        name + " description",
        true,
        Path.of(name),
        Path.of(name).resolve("SKILL.md"),
        name + " instructions");
  }
}
