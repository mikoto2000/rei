package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** チャットGPTモデル選択肢の結果と状態の契約を検証するテスト。 */
class ChatGptModelChoicesTest {
  /** ChatGPTとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void advertisesTheCompleteSortedCurrentAdministratorChoicesWithoutChangingTheirIds() {
    var choices =
        ChatGptModelChoices.models(
            new PlanDelegationProperties(
                true, Set.of("default"), Set.of("model_Z", "model.a", "model-1")));
    assertThat(choices).containsExactly("model-1", "model.a", "model_Z");
    assertThatThrownBy(() -> choices.add("invented"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ChatGPTとの連携の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void oversizedOrUnsafeConfigurationSkipsChoicesInsteadOfAdvertisingAPartialPermissionSet() {
    var models = new java.util.HashSet<String>();
    java.util.stream.IntStream.range(0, 20)
        .forEach(
            index -> models.add("model-" + String.format(java.util.Locale.ROOT, "%02d", index)));
    assertThat(
            ChatGptModelChoices.models(
                new PlanDelegationProperties(true, Set.of("default"), models)))
        .isEmpty();
    for (String unsafe : Set.of("not a model", "https://private.example/", "x".repeat(129)))
      assertThat(
              ChatGptModelChoices.models(
                  new PlanDelegationProperties(true, Set.of("default"), Set.of("valid", unsafe))))
          .isEmpty();
  }

  /** ChatGPTとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void absentDisabledAndEmptyConfigurationOfferNoChoices() {
    assertThat(ChatGptModelChoices.models(null)).isEmpty();
    assertThat(
            ChatGptModelChoices.models(
                new PlanDelegationProperties(false, Set.of("default"), Set.of("model"))))
        .isEmpty();
    assertThat(
            ChatGptModelChoices.models(
                new PlanDelegationProperties(true, Set.of("default"), Set.of())))
        .isEmpty();
  }
}
