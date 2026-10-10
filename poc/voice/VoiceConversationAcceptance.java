package dev.mikoto2000.rei;
import java.nio.file.*;
import org.springframework.boot.SpringApplication;
import dev.mikoto2000.rei.voice.*;
public class VoiceConversationAcceptance {
 /** Real Agent smoke test with synthetic input envelopes; never opens the microphone. */
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("ISOLATED_DATA EXTERNAL_CONFIG");
  System.setProperty("rei.data-dir",Path.of(args[0]).toAbsolutePath().toString());
  var app=new SpringApplication(ReiApplication.class);app.setDefaultProperties(ExternalConfigSupport.defaultProperties());
  dev.mikoto2000.rei.web.WebApplication.configure(app,null);
  app.addInitializers(context->context.addBeanFactoryPostProcessor(factory->{
   if(factory instanceof org.springframework.beans.factory.support.BeanDefinitionRegistry registry){
    String name=org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
    if(registry.containsBeanDefinition(name))registry.removeBeanDefinition(name);
   }
  }));
  try(var context=app.run("--spring.config.additional-location=optional:file:"+Path.of(args[1]).toAbsolutePath().toString().replace('\\','/'),
   "--spring.shell.interactive.enabled=false","--spring.shell.noninteractive.enabled=false",
   "--rei.embedding.enabled=false","--rei.activity.enabled=false","--rei.interest.enabled=false",
   "--rei.topic-generator.enabled=false","--rei.sound-notification.enabled=false",
   "--rei.memory.auto-sleep.enabled=false","--rei.today.enabled=false","--rei.task-manager.enabled=false",
   "--spring.ai.mcp.client.enabled=false")) {
   var voice=context.getBean(VoiceInputCoordinator.class);
   var options=context.getBean(VoiceProperties.class).advanced();
   if(voice.state()!=VoiceInputCoordinator.State.OFF||options.wakeEnabled()||options.ttsEnabled()||options.interruptEnabled())throw new AssertionError("Default must be OFF");
   if(context.getBean(IsolatedVoiceBackendFactory.class).liveWorkers()!=0)throw new AssertionError("No native workers expected");
   var projects=context.getBean(dev.mikoto2000.rei.core.project.ProjectService.class);
   var shell=context.getBean(dev.mikoto2000.rei.application.session.ShellConversationService.class);
   var turns=context.getBean(dev.mikoto2000.rei.conversation.ConversationTurnStore.class);
   var delivery=context.getBean(VoiceDeliveryService.class);
   try(var clientScope=projects.newClient().open()) {
    shell.responseStyle(dev.mikoto2000.rei.core.chat.ResponseStyle.NORMAL,false);
    var normal=shell.submit("こんにちは。短く挨拶してください。");
    awaitReply(turns,normal.conversationId(),"こんにちは。短く挨拶してください。","NORMAL_TEXT");
    shell.responseStyle(dev.mikoto2000.rei.core.chat.ResponseStyle.CONVERSATION,false);
    var text=shell.submit("会話モードです。気軽に一言挨拶してください。");
    if(text.responseStyle()!=dev.mikoto2000.rei.core.chat.ResponseStyle.CONVERSATION||text.mode()!=dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE)throw new AssertionError("Style or authority mismatch");
    awaitReply(turns,text.conversationId(),"会話モードです。気軽に一言挨拶してください。","CONVERSATION_TEXT");
    shell.responseStyle(dev.mikoto2000.rei.core.chat.ResponseStyle.CONVERSATION,true);
    var target=shell.captureTarget();delivery.bind(shell.captureClient(),target);
    var phrase="音声入力としての会話テストです。短く挨拶してください。";
    delivery.accept(new dev.mikoto2000.rei.application.input.ConversationInput(java.util.UUID.randomUUID(),dev.mikoto2000.rei.application.input.InputSource.VOICE,target,phrase,java.time.Instant.now()));
    awaitReply(turns,target.sessionId(),phrase,"CONVERSATION_VOICE_ENVELOPE");
    shell.responseStyle(dev.mikoto2000.rei.core.chat.ResponseStyle.NORMAL,false);
   }
   context.getBean(VoicePlaybackService.class).stop();
   if(voice.state()!=VoiceInputCoordinator.State.OFF||context.getBean(IsolatedVoiceBackendFactory.class).liveWorkers()!=0)throw new AssertionError("Cleanup mismatch");
   System.out.println("PHASE7_ACCEPTANCE_OK; real_Agent_text_and_voice_envelope; voice_OFF; native_workers_0; no_microphone_or_playback");
  }
 }
 private static void awaitReply(dev.mikoto2000.rei.conversation.ConversationTurnStore turns,String session,String request,String label)throws Exception {
  long deadline=System.nanoTime()+java.time.Duration.ofMinutes(3).toNanos();
  while(System.nanoTime()-deadline<0) {
   var turn=turns.read(session).stream().filter(t->request.equals(t.request())).reduce((a,b)->b);
   if(turn.isPresent()&&turn.get().status()!=dev.mikoto2000.rei.conversation.ConversationTurnStore.Status.RUNNING) {
    if(turn.get().status()!=dev.mikoto2000.rei.conversation.ConversationTurnStore.Status.COMPLETED||turn.get().assistantMessage()==null||turn.get().assistantMessage().isBlank())throw new IllegalStateException(label+" failed");
    System.out.println(label+" COMPLETED: "+turn.get().assistantMessage());return;
   }
   Thread.sleep(100);
  }
  throw new IllegalStateException(label+" timed out");
 }

}
