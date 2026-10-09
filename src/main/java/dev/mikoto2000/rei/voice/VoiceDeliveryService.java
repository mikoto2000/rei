package dev.mikoto2000.rei.voice;
import java.time.Clock;
import java.util.*;
import dev.mikoto2000.rei.application.input.*;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.project.ProjectClient;
/** One Shell-owned microphone binding, separate from all other clients and from tool approval. */
public final class VoiceDeliveryService {
  private record Binding(ProjectClient client,ConversationTarget target){}
  private volatile Binding binding;
  private final ShellConversationService shell;
  private final VoiceReviewInbox inbox;
  private final VoiceEventPublisher events;
  public VoiceDeliveryService(ShellConversationService shell,Clock clock,VoiceEventPublisher events) {
    this.shell=Objects.requireNonNull(shell);this.events=Objects.requireNonNull(events);
    inbox=new VoiceReviewInbox(this::dispatch,i->targetIsCurrent(i.target()),clock);
  }
  public void bind(ProjectClient client,ConversationTarget target) {
    if(client==null||target==null||!shell.isSelected(client,target))throw new IllegalStateException("Current Shell client/Session required");
    var previous=binding;
    if(previous!=null&&(previous.client()!=client||!previous.target().equals(target)))inbox.clear();
    binding=new Binding(client,target);
  }
  public boolean targetIsCurrent(ConversationTarget target) {
    var selected=binding;return selected!=null&&selected.target().equals(target)&&shell.isSelected(selected.client(),target);
  }
  private void dispatch(ConversationInput input) {
    var selected=binding;
    if(selected==null||!selected.target().equals(input.target()))throw new IllegalStateException("Voice target changed");
    shell.submitSelectedVoice(selected.client(),input);
  }
  public void accept(ConversationInput input) {
    inbox.accept(input);if(inbox.confirmation())events.publish(VoiceEventPublisher.Type.REVIEW_REQUIRED,input.inputId().toString());
  }
  public void setConfirmation(boolean value){inbox.setConfirmation(value);}
  public boolean confirmation(){return inbox.confirmation();}
  public List<ConversationInput> pending(){return inbox.pending();}
  public boolean cancel(UUID id){return inbox.cancel(id);}
  public void confirm(UUID id,String correction){inbox.confirm(id,correction);}
  public void clear(){inbox.clear();}
}