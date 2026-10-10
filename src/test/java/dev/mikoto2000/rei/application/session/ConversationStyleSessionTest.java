package dev.mikoto2000.rei.application.session;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.application.input.*;
import static org.assertj.core.api.Assertions.*;
class ConversationStyleSessionTest {
 @TempDir Path root;
 @Test void defaultStyleAutomaticallySelectsVoiceAndExplicitNormalOverridesIt() {
  var file=root.resolve("sessions.json");var lifecycle=new SessionLifecycle(new FileSessionRepository(file),Clock.systemUTC());
  var project=new ProjectContext("550e8400-e29b-41d4-a716-446655440000","p",root);
  var session=lifecycle.create(project,"auto");
  var text=lifecycle.submit(project,session.sessionId(),"text",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.EXCLUSIVE,false,c->{});
  var voice=lifecycle.submit(project,session.sessionId(),"voice",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.EXCLUSIVE,true,c->{});
  assertThat(text.responseStyle()).isEqualTo(ResponseStyle.NORMAL);
  assertThat(voice.responseStyle()).isEqualTo(ResponseStyle.CONVERSATION);
  assertThat(voice.mode()).isEqualTo(AgentRunContext.Mode.EXCLUSIVE);
  assertThat(lifecycle.submit(project,null,"first voice",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.EXCLUSIVE,true,c->{}).responseStyle()).isEqualTo(ResponseStyle.CONVERSATION);
  lifecycle.responseStyle(project,session.sessionId(),ResponseStyle.NORMAL,false);
  var resumed=new SessionLifecycle(new FileSessionRepository(file),Clock.systemUTC());
  assertThat(resumed.submit(project,session.sessionId(),"voice",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.EXCLUSIVE,true,c->{}).responseStyle()).isEqualTo(ResponseStyle.NORMAL);
 }
 @Test void preferencesPersistAcrossResumeAndAreCapturedForTextAndVoice() {
  var projects=new ProjectService(root,new ProjectRegistry(root.resolve("projects.json")));
  var file=root.resolve("sessions.json");var repository=new FileSessionRepository(file);
  var lifecycle=new SessionLifecycle(repository,Clock.systemUTC());
  var accepted=new ArrayList<AgentRunContext>();
  var jobs=new ArrayList<Runnable>();
  var router=new ConversationInputRouter(jobs::add,(c,p,q)->{});
  var shell=new ShellConversationService(projects,lifecycle,(c,p)->{accepted.add(c);router.submit(c,p);},false,router);
  try(var scope=projects.newClient().open()) {
   assertThat(shell.currentMetadata()).isNull();
   shell.responseStyle(ResponseStyle.CONVERSATION,false);var id=shell.currentSessionId();
   var text=shell.submit("こんにちは");var target=shell.captureTarget();
   var voice=shell.submitSelectedVoice(shell.captureClient(),new ConversationInput(UUID.randomUUID(),InputSource.VOICE,target,"詳しく説明して",Instant.now()));
   assertThat(text.responseStyle()).isEqualTo(ResponseStyle.CONVERSATION);
   assertThat(voice.responseStyle()).isEqualTo(ResponseStyle.CONVERSATION);
   assertThat(text.mode()).isEqualTo(AgentRunContext.Mode.EXCLUSIVE);assertThat(voice.voiceInput()).isTrue();
   shell.newConversation();assertThat(shell.submit("別の会話").responseStyle()).isEqualTo(ResponseStyle.NORMAL);
   shell.resume(id);assertThat(shell.currentMetadata().responseStyle()).isEqualTo(ResponseStyle.CONVERSATION);
   shell.responseStyle(ResponseStyle.NORMAL,false);
   assertThat(shell.submit("通常").responseStyle()).isEqualTo(ResponseStyle.NORMAL);
   assertThat(text.responseStyle()).isEqualTo(ResponseStyle.CONVERSATION);
   assertThat(new FileSessionRepository(file).findById(id).orElseThrow().responseStyle()).isEqualTo(ResponseStyle.NORMAL);
  }
 }
 @Test void voiceOnlyKeepsKeyboardNormalAndProjectBoundaryIsEnforced() {
  var repository=new FileSessionRepository(root.resolve("sessions.json"));
  var lifecycle=new SessionLifecycle(repository,Clock.systemUTC());var project=new ProjectContext("550e8400-e29b-41d4-a716-446655440000","p",root);
  var session=lifecycle.create(project,"style");
  lifecycle.responseStyle(project,session.sessionId(),ResponseStyle.CONVERSATION,true);
  var text=lifecycle.submit(project,session.sessionId(),"text",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.EXCLUSIVE,false,c->{});
  var voice=lifecycle.submit(project,session.sessionId(),"voice",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.EXCLUSIVE,true,c->{});
  assertThat(text.responseStyle()).isEqualTo(ResponseStyle.NORMAL);assertThat(voice.responseStyle()).isEqualTo(ResponseStyle.CONVERSATION);
  assertThat(voice.asVoiceInput().responseStyle()).isEqualTo(ResponseStyle.CONVERSATION);
  assertThatThrownBy(()->lifecycle.responseStyle(new ProjectContext("550e8400-e29b-41d4-a716-446655440001","other",root),session.sessionId(),ResponseStyle.NORMAL,false)).isInstanceOf(dev.mikoto2000.rei.application.run.SessionConflictException.class);
 }
 @Test void failedAdmissionRestoresAllStylePreferencesAndTime() {
  var file=root.resolve("sessions.json");var repository=new FileSessionRepository(file);
  var now=Instant.now();var original=new SessionMetadata("s","p","title",now,now,ResponseStyle.CONVERSATION,true);
  repository.accept(original,()->{});
  assertThatThrownBy(()->repository.accept(original.withResponseStyle(ResponseStyle.NORMAL,false,now.plusSeconds(2)),()->{throw new IllegalStateException("rejected");})).hasMessage("rejected");
  assertThat(new FileSessionRepository(file).findById("s")).contains(original);
 }
 @Test void legacySessionAndRunJsonDefaultToNormalAndStyleSurvivesRoundTrip() throws Exception {
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  var legacy=mapper.readValue("{\"sessionId\":\"s\",\"projectId\":\"p\",\"title\":\"old\",\"createdAt\":\"2026-10-10T00:00:00Z\",\"updatedAt\":\"2026-10-10T00:00:00Z\"}",SessionMetadata.class);
  assertThat(legacy.responseStyle()).isEqualTo(ResponseStyle.AUTO);assertThat(legacy.voiceOnly()).isFalse();
  legacy=new SessionMetadata(legacy.sessionId(),"550e8400-e29b-41d4-a716-446655440000",legacy.title(),legacy.createdAt(),legacy.updatedAt(),legacy.responseStyle(),legacy.voiceOnly());
  var project=new ProjectContext(legacy.projectId(),"legacy",root);
  var repository=new FileSessionRepository(root.resolve("legacy.json"));repository.accept(legacy,()->{});
  var lifecycle=new SessionLifecycle(repository,Clock.systemUTC());
  assertThat(lifecycle.submit(project,"s","voice",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.EXCLUSIVE,true,c->{}).responseStyle()).isEqualTo(ResponseStyle.CONVERSATION);
  var run=new AgentRunContext("r","s",root,"p");
  var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(run);json.remove("responseStyle");
  assertThat(mapper.treeToValue(json,AgentRunContext.class).responseStyle()).isEqualTo(ResponseStyle.NORMAL);
  var styled=new AgentRunContext("r","s",root,"p",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.EXCLUSIVE,true,ResponseStyle.CONVERSATION);
  assertThat(mapper.readValue(mapper.writeValueAsString(styled),AgentRunContext.class)).isEqualTo(styled);
 }
}
