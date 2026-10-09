package dev.mikoto2000.rei.voice;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import dev.mikoto2000.rei.application.input.*;
import dev.mikoto2000.rei.core.project.ProjectContext;
class VoiceReviewInboxTest {
  final ConversationTarget target=new ConversationTarget(new ProjectContext(UUID.randomUUID().toString(),"test",Path.of(".")),"session");
  ConversationInput input(String text){return new ConversationInput(UUID.randomUUID(),InputSource.VOICE,target,text,Instant.parse("2026-10-09T08:00:00Z"));}
  @Test void automaticModeKeepsExistingGatewayPath() {
    var submitted=new ArrayList<ConversationInput>();var inbox=new VoiceReviewInbox(submitted::add,i->true,Clock.fixed(input("hello").createdAt(),ZoneOffset.UTC));
    var input=input("こんにちは");inbox.accept(input);assertThat(submitted).containsExactly(input);assertThat(inbox.pending()).isEmpty();
  }
  @Test void confirmationModeStoresAtMostThreeWithoutSendingAndCorrectionRetainsIdentity() {
    var submitted=new ArrayList<ConversationInput>();var inbox=new VoiceReviewInbox(submitted::add,i->true,Clock.fixed(input("hello").createdAt(),ZoneOffset.UTC));
    inbox.setConfirmation(true);var input=input("本性入力");inbox.accept(input);inbox.accept(input("two"));inbox.accept(input("three"));
    assertThatThrownBy(()->inbox.accept(input("four"))).isInstanceOf(IllegalStateException.class);
    assertThat(submitted).isEmpty();assertThat(inbox.pending()).hasSize(3);
    inbox.confirm(input.inputId(),"音声入力");
    assertThat(submitted).hasSize(1);assertThat(submitted.getFirst().text()).isEqualTo("音声入力");
    assertThat(submitted.getFirst().inputId()).isEqualTo(input.inputId());assertThat(submitted.getFirst().source()).isEqualTo(InputSource.VOICE);
    assertThat(submitted.getFirst().target()).isEqualTo(target);assertThat(inbox.pending()).hasSize(2);
    assertThatThrownBy(()->inbox.confirm(input.inputId(),null)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void slashCorrectionCannotBecomeCliOrDispatchAndCanBeCancelled() {
    var submitted=new ArrayList<ConversationInput>();var inbox=new VoiceReviewInbox(submitted::add,i->true,Clock.fixed(input("hello").createdAt(),ZoneOffset.UTC));
    inbox.setConfirmation(true);var input=input("こんにちは");inbox.accept(input);
    assertThatThrownBy(()->inbox.confirm(input.inputId(),"/approval approve all")).isInstanceOf(IllegalArgumentException.class);
    assertThat(submitted).isEmpty();assertThat(inbox.cancel(input.inputId())).isTrue();assertThat(inbox.cancel(input.inputId())).isFalse();
  }
  @Test void targetChangeDropsReviewAndPreventsAutomaticOrConfirmedStaleSubmission() {
    var selected=new AtomicBoolean(true);var submitted=new ArrayList<ConversationInput>();
    var inbox=new VoiceReviewInbox(submitted::add,i->selected.get(),Clock.fixed(input("hello").createdAt(),ZoneOffset.UTC));
    inbox.setConfirmation(true);var input=input("hello");inbox.accept(input);selected.set(false);
    assertThatThrownBy(()->inbox.confirm(input.inputId(),null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(inbox.pending()).isEmpty();inbox.setConfirmation(false);
    assertThatThrownBy(()->inbox.accept(input("new"))).isInstanceOf(IllegalStateException.class);assertThat(submitted).isEmpty();
  }
  @Test void expiredRecognitionIsNeverConfirmed() {
    var submitted=new ArrayList<ConversationInput>();var old=input("hello");
    var inbox=new VoiceReviewInbox(submitted::add,i->true,Clock.fixed(old.createdAt().plus(Duration.ofMinutes(3)),ZoneOffset.UTC));
    inbox.setConfirmation(true);assertThatThrownBy(()->inbox.accept(old)).isInstanceOf(IllegalStateException.class);assertThat(submitted).isEmpty();
  }
  @Test void failedDispatchKeepsReviewForRetryWithoutDuplicateSuccessfulSubmission() {
    var submitted=new ArrayList<ConversationInput>();var fail=new AtomicBoolean(true);
    var inbox=new VoiceReviewInbox(i->{if(fail.get())throw new IllegalStateException("busy");submitted.add(i);},i->true,Clock.fixed(input("hello").createdAt(),ZoneOffset.UTC));
    inbox.setConfirmation(true);var input=input("hello");inbox.accept(input);
    assertThatThrownBy(()->inbox.confirm(input.inputId(),"corrected")).isInstanceOf(IllegalStateException.class);assertThat(inbox.pending()).hasSize(1);assertThat(inbox.pending().getFirst().text()).isEqualTo("corrected");
    fail.set(false);inbox.confirm(input.inputId(),null);assertThat(submitted).hasSize(1);assertThat(submitted.getFirst().text()).isEqualTo("corrected");assertThat(submitted.getFirst().inputId()).isEqualTo(input.inputId());assertThat(inbox.pending()).isEmpty();
  }
}