package dev.mikoto2000.rei.chatgpt;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChatGptModelChoicesTest {
  @Test void advertisesTheCompleteSortedCurrentAdministratorChoicesWithoutChangingTheirIds() {
    var choices=ChatGptModelChoices.models(new PlanDelegationProperties(true,Set.of("default"),Set.of("model_Z","model.a","model-1")));
    assertThat(choices).containsExactly("model-1","model.a","model_Z");
    assertThatThrownBy(()->choices.add("invented")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test void oversizedOrUnsafeConfigurationSkipsChoicesInsteadOfAdvertisingAPartialPermissionSet() {
    var models=new java.util.HashSet<String>();
    java.util.stream.IntStream.range(0,20).forEach(index->models.add("model-"+String.format(java.util.Locale.ROOT,"%02d",index)));
    assertThat(ChatGptModelChoices.models(new PlanDelegationProperties(true,Set.of("default"),models))).isEmpty();
    for(String unsafe:Set.of("not a model","https://private.example/","x".repeat(129)))
      assertThat(ChatGptModelChoices.models(new PlanDelegationProperties(true,Set.of("default"),Set.of("valid",unsafe)))).isEmpty();
  }

  @Test void absentDisabledAndEmptyConfigurationOfferNoChoices() {
    assertThat(ChatGptModelChoices.models(null)).isEmpty();
    assertThat(ChatGptModelChoices.models(new PlanDelegationProperties(false,Set.of("default"),Set.of("model")))).isEmpty();
    assertThat(ChatGptModelChoices.models(new PlanDelegationProperties(true,Set.of("default"),Set.of()))).isEmpty();
  }
}
