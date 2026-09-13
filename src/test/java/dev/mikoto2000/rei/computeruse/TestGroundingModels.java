package dev.mikoto2000.rei.computeruse;

/** Geometry/protocol unit tests use a trusted verifier; rejection is tested separately. */
final class TestGroundingModels {
  static ShowUiComputerVisionModel create(ComputerVisionModel planner, org.springframework.ai.chat.model.ChatModel model,
      java.util.function.Supplier<org.springframework.ai.openai.OpenAiChatOptions.Builder> options,
      java.util.function.BooleanSupplier cancelled) {
    return create(planner,model,options,cancelled,GroundingProtocol.SHOWUI);
  }
  static ShowUiComputerVisionModel create(ComputerVisionModel planner, org.springframework.ai.chat.model.ChatModel model,
      java.util.function.Supplier<org.springframework.ai.openai.OpenAiChatOptions.Builder> options,
      java.util.function.BooleanSupplier cancelled, GroundingProtocol protocol) {
    return new ShowUiComputerVisionModel(planner,model,options,cancelled,protocol,(o,i,t,p)->{});
  }
}
