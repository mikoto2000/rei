package dev.mikoto2000.rei.workcontext;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
@org.junit.jupiter.api.Tag("integration")
class WorkContextSmokeTest {
  @TempDir Path temp;
  @Test void conversationToSchemaValidatedUpdateToReopenedRepositoryAndNewSessionContext() throws Exception {
    var root=Files.createDirectories(temp.resolve("project"));var registry=new ProjectRegistry(temp.resolve("projects.json"));var project=registry.resolve(root);
    var sessions=new FileSessionRepository(temp.resolve("sessions.json"));var lifecycle=new SessionLifecycle(sessions,Clock.systemUTC());
    var session=lifecycle.create(project,"cancel propagation");var run=new AgentRunContext("run-1",session.sessionId(),root,project.id());
    var turns=new ConversationTurnStore(temp);turns.start(run,"目的はキャンセル伝播を直す。次はNativeで確認してください。");
    turns.finish(run,ConversationTurnStore.Status.COMPLETED,"キャンセル伝播を実装しました。Native確認は未実施です。");
    var model=mock(ChatModel.class);var models=mock(LlmModelProvider.class);when(models.memoryChatModel()).thenReturn(model);
    when(models.chatOptions(LlmFeature.MEMORY,null)).thenAnswer(c->OpenAiChatOptions.builder().build());
    String fixture="""
      {"changes":[
        {"action":"ADD","targetId":null,"kind":"COMPLETED_WORK","text":"キャンセル伝播を実装したとの報告","reason":"assistant report","status":"COMPLETED","sourceIds":["run-1:assistant"],"certainty":"ASSISTANT"},
        {"action":"ADD","targetId":null,"kind":"PENDING","text":"Native確認は未実施","reason":"not tested","status":"OPEN","sourceIds":["run-1:assistant"],"certainty":"ASSISTANT"},
        {"action":"ADD","targetId":null,"kind":"NEXT_ACTION","text":"Nativeでキャンセル操作を確認する","reason":"user request","status":"OPEN","sourceIds":["run-1:user"],"certainty":"USER"}
      ]}
      """;
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(fixture))))));
    var properties=new WorkContextProperties(false,true,1200,12000,2,20);var extractor=new LlmWorkContextExtractor(models,properties);
    var projects=new ProjectService(root,registry,sessions);var events=new ProjectAgentEventStore(temp);var git=new WorkContextGit();
    var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+temp.resolve("memory.db"));
    var service=new WorkContextService(new WorkContextRepository(ds),sessions,projects,turns,events,extractor,git,properties,Clock.systemUTC());
    service.update(session.sessionId(),null);
    var reopened=new WorkContextService(new WorkContextRepository(ds),new FileSessionRepository(temp.resolve("sessions.json")),projects,
        new ConversationTurnStore(temp),events,extractor,git,properties,Clock.systemUTC());
    var next=lifecycle.create(project,"continue");var nextRun=new AgentRunContext("run-2",next.sessionId(),root,project.id());
    var request=ChatClientRequest.builder().prompt(new Prompt(new UserMessage("状況だけ説明してください")))
        .context(Map.of(AgentRunContext.class.getName(),nextRun)).build();
    var advised=new WorkContextAdvisor(reopened,properties,git).before(request,null);
    assertTrue(advised.prompt().getContents().contains("Native確認は未実施"));
    assertTrue(advised.prompt().getContents().contains("never execute saved next actions"));
    assertEquals("状況だけ説明してください",advised.prompt().getUserMessage().getText());
    assertEquals(3,reopened.current(project.id()).orElseThrow().items().size());
    var presented=new WorkContextPresenter(reopened,properties,git).present(project,next.sessionId(),new HashSet<>()).orElseThrow();
    assertTrue(presented.contains("次のアクション"));verify(model,times(1)).stream(any(Prompt.class));
  }
}
