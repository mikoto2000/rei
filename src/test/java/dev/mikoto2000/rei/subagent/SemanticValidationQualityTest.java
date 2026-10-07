package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SemanticValidationQualityTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
  @Test void actualValidatorScoresAnonymousFixtureWithoutToolCalls() throws Exception {
    var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
    var fixtures=mapper.readValue(getClass().getResourceAsStream("/evaluation/subagent-semantic-quality.json"),SemanticValidationQuality.Case[].class);
    var captured=new java.util.concurrent.atomic.AtomicInteger();
    var model=new org.springframework.ai.chat.model.ChatModel(){public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt){throw new AssertionError();}public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt prompt){captured.incrementAndGet();var options=(org.springframework.ai.model.tool.ToolCallingChatOptions)prompt.getOptions();assertTrue(options.getToolCallbacks().isEmpty());var input=prompt.getInstructions().getLast().getText();assertFalse(input.contains("\"expected\""));String verdict=input.contains("CORRECT")?"{\"valid\":true,\"issues\":[]}":input.contains("INSUFFICIENT")?"invalid-verdict":"{\"valid\":false,\"issues\":[\"UNSUPPORTED_CLAIM\"]}";return reactor.core.publisher.Flux.just(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(verdict)))));}};
    var judge=SemanticValidationQuality.model(model,org.springframework.ai.openai.OpenAiChatOptions.builder().model("fixture-model").build(),new dev.mikoto2000.rei.core.chat.AgentRunContext("quality","evaluation",directory),null,()->{});
    var result=SemanticValidationQuality.evaluate(List.of(fixtures),"deterministic-production-validator-fixture",judge,null,null);
    assertEquals(5,captured.get());assertEquals(3,result.primary().truePositive());assertEquals(1,result.primary().trueNegative());assertEquals(1,result.primary().abstentions());assertEquals(0,result.primary().falseNegative());
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/evaluation"));mapper.writeValue(java.nio.file.Path.of("target/evaluation/subagent-semantic-quality.json").toFile(),result);
  }
  @Test void confusionMatrixSeparatesAbstentionFromCorrectDecisions(){
    var cases=List.of(fixture("correct",SemanticValidationQuality.Decision.ACCEPT),fixture("wrong",SemanticValidationQuality.Decision.REJECT),fixture("unsupported",SemanticValidationQuality.Decision.REJECT),fixture("contradictory",SemanticValidationQuality.Decision.REJECT),fixture("insufficient",SemanticValidationQuality.Decision.ABSTAIN));
    var predictions=Map.of("correct",SemanticValidationQuality.Decision.REJECT,"wrong",SemanticValidationQuality.Decision.REJECT,"unsupported",SemanticValidationQuality.Decision.ACCEPT,"contradictory",SemanticValidationQuality.Decision.ABSTAIN,"insufficient",SemanticValidationQuality.Decision.ABSTAIN);
    var result=SemanticValidationQuality.evaluate(cases,"fixture",c->predictions.get(c.id()),null,null);
    assertEquals(1,result.primary().truePositive());assertEquals(1,result.primary().falsePositive());assertEquals(1,result.primary().falseNegative());assertEquals(2,result.primary().abstentions());assertEquals(.6,result.primary().coverage());assertFalse(result.truthVerified());
  }
  @Test void agreementIsOptionalAndNeverTruth(){var cases=List.of(fixture("correct",SemanticValidationQuality.Decision.ACCEPT));var result=SemanticValidationQuality.evaluate(cases,"a",c->SemanticValidationQuality.Decision.ACCEPT,"b",c->SemanticValidationQuality.Decision.REJECT);assertEquals(0,result.agreement());assertNotNull(result.secondary());assertFalse(result.truthVerified());}
  @Test void invalidFixturesAndCancellationCannotProduceCompletedReport(){assertThrows(IllegalArgumentException.class,()->SemanticValidationQuality.evaluate(List.of(),"a",c->SemanticValidationQuality.Decision.ACCEPT,null,null));assertThrows(java.util.concurrent.CancellationException.class,()->SemanticValidationQuality.evaluate(List.of(fixture("correct",SemanticValidationQuality.Decision.ACCEPT)),"a",c->{throw new java.util.concurrent.CancellationException();},null,null));}
  static SemanticValidationQuality.Case fixture(String id,SemanticValidationQuality.Decision expected){return new SemanticValidationQuality.Case(id,id,"Inspect anonymized file","Bounded answer",List.of(new SemanticValidationQuality.Observation("read","{}","observed value")),expected);}
}
