package dev.mikoto2000.rei.core.policy;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.*;
class VoiceToolPermissionTest {
  AgentRunContext text(){return new AgentRunContext("run","session",Path.of("."),"project");}
  ToolPermissionGuard guard(ToolPermissionProperties properties,List<AgentEvent> events) {
    return new ToolPermissionGuard(new ToolPermissionPolicy(properties),new AgentEventFactory(Clock.systemUTC()),events::add);
  }
  @Test void recognizedSpeechNeverTurnsAutomaticSubmissionIntoMutatingToolApproval() {
    var events=new ArrayList<AgentEvent>();var guard=guard(new ToolPermissionProperties(false,null,null,null),events);
    var voice=text().asVoiceInput();
    assertThatCode(()->guard.check("readFile",voice)).doesNotThrowAnyException();
    assertThatCode(()->guard.check("webSearch",voice)).doesNotThrowAnyException();
    for(String tool:List.of("writeMultiFile","deleteFile","executeShellCommand","sendMessage","unknownMcp"))
      assertThatThrownBy(()->guard.check(tool,"{}",voice)).isInstanceOf(ToolPermissionException.class);
    assertThat(events).hasSize(5);
    assertThatCode(()->guard.check("writeMultiFile",text())).doesNotThrowAnyException();
    assertThat(voice.requestSource()).isEqualTo(AgentRunContext.RequestSource.SHELL);assertThat(voice.mode()).isEqualTo(AgentRunContext.Mode.EXCLUSIVE);
  }
  @Test void administratorRelabelingCannotMakeUncertainVoiceMutationReadOnly() {
    var events=new ArrayList<AgentEvent>();var guard=guard(new ToolPermissionProperties(true,Set.of(ActionCapability.READ),Set.of(),Map.of("unknownMcp",Set.of(ActionCapability.READ))),events);
    assertThatThrownBy(()->guard.check("unknownMcp",text().asVoiceInput())).isInstanceOf(ToolPermissionException.class);
    assertThat(events).hasSize(1);
  }
  @Test void existingDenyAndConcurrentModeRestrictionsRemainDeny() {
    var events=new ArrayList<AgentEvent>();var guard=guard(new ToolPermissionProperties(true,Set.of(),Set.of(ActionCapability.DESTRUCTIVE),Map.of()),events);
    assertThatThrownBy(()->guard.check("deleteFile",text().asVoiceInput())).isInstanceOf(ToolPermissionException.class);
    var restricted=new AgentRunContext("run","session",Path.of("."),"project",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.CONVERSATION).asVoiceInput();
    assertThatThrownBy(()->guard.check("readFile",restricted)).isInstanceOf(ToolPermissionException.class);
    assertThat(events).hasSize(2);
  }
  @Test void speechProvenanceRoundTripsButLegacyContextIsText() throws Exception {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var voice=text().asVoiceInput();
    assertThat(mapper.readValue(mapper.writeValueAsString(voice),AgentRunContext.class).voiceInput()).isTrue();
    var legacy=mapper.valueToTree(text());((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove("voiceInput");
    assertThat(mapper.treeToValue(legacy,AgentRunContext.class).voiceInput()).isFalse();
  }
}