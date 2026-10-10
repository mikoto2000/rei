package dev.mikoto2000.rei.voice;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.application.input.*;
import dev.mikoto2000.rei.core.project.ProjectContext;

class VoiceCorrectionServiceTest {
  @Test void pendingUtteranceCanBeCancelledIndividuallyBeforeLlmReturns() throws Exception {
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var input=input("交配の話");
    try(var service=new VoiceCorrectionService(properties(),this::snapshot,(raw,context)->{entered.countDown();release.await();return VoiceCorrectionValidatorTest.corrected(raw,"勾配の話");},new VoiceEventPublisher())) {
      var result=service.correct(input,()->true).toCompletableFuture();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
      assertThat(service.pending()).containsExactly(input);assertThat(service.cancel(input.inputId())).isTrue();
      assertThat(result.join().reason()).isEqualTo("cancelled");assertThat(service.pending()).isEmpty();assertThat(service.cancel(input.inputId())).isFalse();
      release.countDown();
    }finally{release.countDown();}
  }
  final ConversationTarget target=new ConversationTarget(new ProjectContext(UUID.randomUUID().toString(),"test",Path.of(".")),"session");
  ConversationInput input(String text){return new ConversationInput(UUID.randomUUID(),InputSource.VOICE,target,text,Instant.now());}
  VoiceCorrectionProperties properties(){var p=new VoiceCorrectionProperties();p.setEnabled(true);p.setMaxTotalTokens(0);return p;}
  VoiceCorrectionContext.Snapshot snapshot(ConversationInput i){return new VoiceCorrectionContext.Snapshot(i.target().project().id(),"test",List.of("微分の話"),List.of());}
  @Test void adoptsValidCandidateAndBypassesWhenOffOrRawControl() throws Exception {
    var p=properties();var calls=new AtomicInteger();
    try(var service=new VoiceCorrectionService(p,this::snapshot,(raw,context)->{calls.incrementAndGet();return VoiceCorrectionValidatorTest.corrected(raw,"勾配の話");},new VoiceEventPublisher())) {
      assertThat(service.correct(input("交配の話"),()->true).toCompletableFuture().get(2,TimeUnit.SECONDS).text()).isEqualTo("勾配の話");
      for(String raw:List.of("実行を停止。","はい","いいえ"))assertThat(service.correct(input(raw),()->true).toCompletableFuture().join().text()).isEqualTo(raw);
      p.setEnabled(false);assertThat(service.correct(input("交配の話"),()->true).toCompletableFuture().join().reason()).isEqualTo("bypassed");
      assertThat(calls).hasValue(1);
    }
  }
  @Test void deadlineWinsOnceEvenIfTransportIgnoresInterruption() throws Exception {
    var timer=mock(ScheduledExecutorService.class);var actions=new CopyOnWriteArrayList<Runnable>();
    when(timer.schedule(any(Runnable.class),anyLong(),eq(TimeUnit.MILLISECONDS))).thenAnswer(invocation->{actions.add(invocation.getArgument(0));return mock(ScheduledFuture.class);});
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var events=new CopyOnWriteArrayList<VoiceEventPublisher.Event>();
    var publisher=new VoiceEventPublisher();publisher.subscribe(events::add);
    try(var service=new VoiceCorrectionService(properties(),this::snapshot,(raw,context)->{
      entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}
      return VoiceCorrectionValidatorTest.corrected(raw,"勾配の話");
    },publisher,timer,()->0)) {
      var result=service.correct(input("交配の話"),()->true).toCompletableFuture();var completions=new AtomicInteger();result.thenRun(completions::incrementAndGet);
      assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();actions.getFirst().run();
      assertThat(result.join().reason()).isEqualTo("timeout");release.countDown();
      assertThat(completions).hasValue(1);
      assertThat(events.stream().filter(e->e.type()==VoiceEventPublisher.Type.CORRECTION_RESULT)).hasSize(1);
    }finally{release.countDown();}
  }
  @Test void queueIsBoundedAndSnapshotsAreTakenBeforeQueueing() throws Exception {
    var p=properties();p.setMaxQueuedRequests(1);
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var captures=new AtomicInteger();
    var contexts=new CopyOnWriteArrayList<VoiceCorrectionContext.Snapshot>();
    try(var service=new VoiceCorrectionService(p,i->new VoiceCorrectionContext.Snapshot(target.project().id(),"snapshot-"+captures.incrementAndGet(),List.of(),List.of()),(raw,context)->{
      contexts.add(context);entered.countDown();release.await();return VoiceCorrectionValidatorTest.corrected(raw,"勾配の話");
    },new VoiceEventPublisher())) {
      var first=service.correct(input("交配の話"),()->true).toCompletableFuture();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
      var second=service.correct(input("交配の話"),()->true).toCompletableFuture();
      assertThat(service.correct(input("交配の話"),()->true).toCompletableFuture().join().reason()).isEqualTo("queue_full");
      release.countDown();assertThat(first.get(2,TimeUnit.SECONDS).text()).isEqualTo("勾配の話");assertThat(second.get(2,TimeUnit.SECONDS).text()).isEqualTo("勾配の話");
      assertThat(contexts).extracting(VoiceCorrectionContext.Snapshot::projectName).containsExactly("snapshot-1","snapshot-2");
    }finally{release.countDown();}
  }
  @Test void cancellationAndSelectionChangeInvalidateLateCandidate() throws Exception {
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var current=new AtomicBoolean(true);
    try(var service=new VoiceCorrectionService(properties(),this::snapshot,(raw,context)->{entered.countDown();release.await();return VoiceCorrectionValidatorTest.corrected(raw,"勾配の話");},new VoiceEventPublisher())) {
      var result=service.correct(input("交配の話"),current::get).toCompletableFuture();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
      current.set(false);release.countDown();assertThat(result.get(2,TimeUnit.SECONDS).reason()).isEqualTo("cancelled");
    }finally{release.countDown();}
  }
  @Test void failuresAndSecretsNeverEnterDiagnosticsAndCriticalRawRequiresReview() throws Exception {
    var events=new CopyOnWriteArrayList<VoiceEventPublisher.Event>();var publisher=new VoiceEventPublisher();publisher.subscribe(events::add);var calls=new AtomicInteger();
    try(var service=new VoiceCorrectionService(properties(),this::snapshot,(raw,context)->{calls.incrementAndGet();throw new IllegalStateException("password=topsecret "+raw);},publisher)) {
      var result=service.correct(input("対象を削除"),()->true).toCompletableFuture().get(2,TimeUnit.SECONDS);
      assertThat(result.text()).isEqualTo("対象を削除");assertThat(result.confirmationRequired()).isTrue();
      assertThat(service.correct(input("password=topsecret"),()->true).toCompletableFuture().join().reason()).isEqualTo("sensitive_input");
      assertThat(calls).hasValue(1);assertThat(events.toString()).doesNotContain("topsecret","対象を削除","password");
    }
  }
}
