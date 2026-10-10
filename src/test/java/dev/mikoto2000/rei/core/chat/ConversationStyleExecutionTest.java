package dev.mikoto2000.rei.core.chat;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ConversationStyleExecutionTest {
 @TempDir Path root;
 final List<Prompt> prompts=new ArrayList<>();final AtomicInteger tools=new AtomicInteger();
 ChatExecutionService service(String tool,String arguments,String answer,ToolPermissionGuard guard) {
  ChatModel model=new ChatModel() {
   public ToolCallingChatOptions getOptions(){return ToolCallingChatOptions.builder().build();}
   public ChatResponse call(Prompt p){throw new UnsupportedOperationException();}
   public Flux<ChatResponse> stream(Prompt p){
    prompts.add(p);
    boolean observed=p.getInstructions().stream().anyMatch(m->m instanceof ToolResponseMessage);
    var output=observed?new AssistantMessage(answer):AssistantMessage.builder().content("")
      .toolCalls(List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",tool,arguments))).build();
    return Flux.just(new ChatResponse(List.of(new Generation(output))));
   }
  };
  ToolCallback callback=new ToolCallback() {
   public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(tool).description("fixture").inputSchema("{\"type\":\"object\"}").build();}
   public String call(String input){tools.incrementAndGet();try {
    if(tool.equals("readFile"))return Files.readString(root.resolve("result.txt"));
    Files.writeString(root.resolve("result.txt"),input);return "verified fixture file written";
   }catch(Exception e){throw new IllegalStateException(e);}}
  };
  var client=ChatClient.builder(new StagnationChatModel(model)).defaultSystem("あなたはれいです。既存キャラクター。").defaultTools(callback)
      .defaultAdvisors(new RunAwareToolCallingAdvisor()).build();
  var holder=mock(ModelHolderService.class);when(holder.get()).thenReturn("test");
  var service=new ChatExecutionService(client,holder,new CommandCancellationService(),Optional.empty());service.setToolPermissionGuard(guard);return service;
 }
 AgentRunContext owner(String run,boolean voice,ResponseStyle style){return new AgentRunContext(run,"session",root,"project",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.EXCLUSIVE,voice,style);}
 @ParameterizedTest @ValueSource(booleans={false,true})
 void sameToolLoopAndFullImportantAnswerWorkForBothInputSources(boolean voice)throws Exception {
  Files.writeString(root.resolve("result.txt"),"proof");
  var guard=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(false,null,null,null)),new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),e->{});
  var answer="重要な検証結果、失敗理由と承認事項を保持します。\n".repeat(400);
  var service=service("readFile","{}",answer,guard);
  var result=service.execute(owner("conversation",voice,ResponseStyle.CONVERSATION),"詳しく調査して",new UserInterventionQueue());
  assertThat(result.success()).isTrue();assertThat(tools.get()).isEqualTo(1);assertThat(result.text()).isEqualTo(answer);
  assertThat(prompts).hasSize(2).allSatisfy(p->assertThat(p.getSystemMessage().getText()).contains("既存キャラクター","会話スタイル"));
  assertThat(service.execute(owner("normal",voice,ResponseStyle.NORMAL),"通常に戻す",new UserInterventionQueue()).success()).isTrue();
  assertThat(prompts.getLast().getSystemMessage().getText()).doesNotContain("会話スタイル");
 }
 @Test void voiceMutationStillRequiresExactOneUseHumanApproval()throws Exception {
  var approvals=new ToolApprovalRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("approvals.db")),Clock.systemUTC());
  var guard=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(false,null,null,null)),new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),e->{});guard.setApprovals(approvals);
  var service=service("writeMultiFile","{\"content\":\"approved fixture\"}","作業を実行して検証しました",guard);
  assertThat(service.execute(owner("blocked",true,ResponseStyle.CONVERSATION),"ファイルを書いて",new UserInterventionQueue()).success()).isFalse();
  assertThat(tools.get()).isZero();assertThat(root.resolve("result.txt")).doesNotExist();
  var request=approvals.list("project").getFirst();approvals.decide("project",request.id(),true);
  assertThat(service.execute(owner("approved",true,ResponseStyle.CONVERSATION),"再開して",new UserInterventionQueue()).success()).isTrue();
  assertThat(tools.get()).isEqualTo(1);assertThat(Files.readString(root.resolve("result.txt"))).contains("approved fixture");
  assertThat(service.execute(owner("consumed",true,ResponseStyle.CONVERSATION),"もう一度",new UserInterventionQueue()).success()).isFalse();assertThat(tools.get()).isEqualTo(1);
 }
}
