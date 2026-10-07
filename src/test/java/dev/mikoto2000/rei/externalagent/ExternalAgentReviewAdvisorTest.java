package dev.mikoto2000.rei.externalagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalAgentReviewAdvisorTest {
  @Test void materialReviewDispatchesOnceAndKeepsReportInEphemeralSystemContext() throws Exception {
    var run = mock(RunExecutionContext.class);
    when(run.userRequest()).thenReturn("/agent codex material-review docs");
    var report = MaterialReviewReport.parse(MaterialReviewSpecificationTest.fixture());
    var result = new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS, report.summary(), report.findings(), report.warnings(),
        1, 0, "PRIVATE LOG", null, null, null, null, null, report.report());
    var service = mock(ExternalAgentDelegationService.class);
    when(service.review(eq(run), anyString(), eq("docs"), anyString())).thenReturn(result);
    var options = OpenAiChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY, run)).build();
    var request = ChatClientRequest.builder().prompt(new Prompt(List.of(new SystemMessage("system"),
        new UserMessage(run.userRequest())), options)).build();
    var advisor = new ExternalAgentReviewAdvisor(service);
    var evaluated = advisor.before(request, null);
    when(run.externalReviewResult()).thenReturn(result.forEvaluation());
    advisor.before(request, null);
    verify(service, times(1)).review(eq(run), anyString(), eq("docs"), anyString());
    assertTrue(evaluated.prompt().getSystemMessage().getText().contains("# 勉強会資料レビュー"));
    assertFalse(evaluated.prompt().getSystemMessage().getText().contains("PRIVATE LOG"));
    assertEquals(request.prompt().getUserMessage(), evaluated.prompt().getUserMessage());
  }
  @Test void slashCallsSharedServiceThenInjectsReviewIntoSystemOnly() {
    var run = mock(RunExecutionContext.class);
    when(run.userRequest()).thenReturn("/agent codex review docs/api.md");
    var service = mock(ExternalAgentDelegationService.class);
    when(service.review(eq(run), anyString(), eq("docs/api.md"), anyString())).thenReturn(
        new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS, "Review finding", List.of(), List.of(), 5, 0, "RAW SECRET LOG"));
    var options = OpenAiChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY, run)).build();
    var request = ChatClientRequest.builder().prompt(new Prompt(List.of(new SystemMessage("system"),
        new AssistantMessage("採用: HTTPS を必須とする"), new UserMessage("/agent codex review docs/api.md")), options)).build();
    var result = new ExternalAgentReviewAdvisor(service).before(request, null);
    verify(service).review(eq(run), anyString(), eq("docs/api.md"), contains("HTTPS"));
    assertEquals(request.prompt().getUserMessage().getText(), result.prompt().getUserMessage().getText());
    assertTrue(result.prompt().getSystemMessage().getText().contains("Review finding"));
    assertTrue(result.prompt().getSystemMessage().getText().contains("independently"));
    assertFalse(result.prompt().getSystemMessage().getText().contains("RAW SECRET LOG"));
  }
  @Test void normalConversationDoesNotDispatch() {
    var run = mock(RunExecutionContext.class);
    when(run.userRequest()).thenReturn("review this design");
    var service = mock(ExternalAgentDelegationService.class);
    var options = OpenAiChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY, run)).build();
    var request = ChatClientRequest.builder().prompt(new Prompt(new UserMessage("review"), options)).build();
    new ExternalAgentReviewAdvisor(service).before(request, null);
    verifyNoInteractions(service);
  }
}
