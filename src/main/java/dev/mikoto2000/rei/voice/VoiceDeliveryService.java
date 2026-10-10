package dev.mikoto2000.rei.voice;
import java.time.Clock;
import java.util.*;
import dev.mikoto2000.rei.application.input.*;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.project.ProjectClient;
/** One Shell-owned microphone binding, separate from all other clients and from tool approval. */
public final class VoiceDeliveryService {
  private record Binding(ProjectClient client,ConversationTarget target,long epoch){}
  private volatile Binding binding;
  private final ShellConversationService shell;
  private final VoiceReviewInbox inbox;
  private final VoiceEventPublisher events;
  private final java.util.function.Supplier<VoiceAdvancedOptions> advanced;
  private final Clock clock;
  private final Map<UUID,ConversationInput> stopped=new LinkedHashMap<>();
  private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<ConversationInput>> submittedListeners
      = new java.util.concurrent.CopyOnWriteArrayList<>();
  public interface Subscription extends AutoCloseable { @Override void close(); }
  /**
   * Shell-only presentation observer; transcripts never enter the diagnostic publisher.
   * May run on the agent worker before accept/confirm returns. Listeners must only
   * render and must not call back into delivery, selection or input admission.
   */
  public Subscription onSubmitted(java.util.function.Consumer<ConversationInput> listener) {
    submittedListeners.add(Objects.requireNonNull(listener));
    return () -> submittedListeners.remove(listener);
  }
  private void submitted(ConversationInput input) {
    for (var listener : submittedListeners) {
      try { listener.accept(input); }
      catch (RuntimeException ignored) {
        // Display failures cannot reject or retry an already admitted conversation input.
      }
    }
  }
  public VoiceDeliveryService(ShellConversationService shell,Clock clock,VoiceEventPublisher events) {
    this(shell,clock,events,VoiceAdvancedOptions::defaults);
  }
  public VoiceDeliveryService(ShellConversationService shell,Clock clock,VoiceEventPublisher events,
      java.util.function.Supplier<VoiceAdvancedOptions> advanced) {
    this.advanced=Objects.requireNonNull(advanced);this.clock=Objects.requireNonNull(clock);
    this.shell=Objects.requireNonNull(shell);this.events=Objects.requireNonNull(events);
    inbox=new VoiceReviewInbox(this::dispatch,i->targetIsCurrent(i.target()),clock);
  }
  public void bind(ProjectClient client,ConversationTarget target) {
    if(client==null||target==null||!shell.isSelected(client,target))throw new IllegalStateException("Current Shell client/Session required");
    var previous=binding;
    if(previous!=null&&(previous.client()!=client||!previous.target().equals(target)))inbox.clear();
    binding=new Binding(client,target,client.selectionEpoch());
  }
  public boolean targetIsCurrent(ConversationTarget target) {
    var selected=binding;return selected!=null&&selected.target().equals(target)&&selected.epoch()==selected.client().selectionEpoch()&&shell.isSelected(selected.client(),target);
  }
  public boolean ownsRun(dev.mikoto2000.rei.core.chat.AgentRunContext context) {
    var selected=binding;
    return selected!=null && context.voiceInput()
        && Objects.equals(context.projectId(),selected.target().project().id())
        && context.projectRoot().equals(selected.target().project().root().toAbsolutePath().normalize())
        && Objects.equals(context.conversationId(),selected.target().sessionId())
        && targetIsCurrent(selected.target()) && shell.ownsRun(selected.client(),context);
  }
  private void dispatch(ConversationInput input) {
    var selected=binding;
    if(selected==null||!selected.target().equals(input.target()))throw new IllegalStateException("Voice target changed");
    synchronized(selected.client()) {
    if(!targetIsCurrent(input.target()))throw new IllegalStateException("Voice selection generation changed");
    synchronized(stopped) {
      stopped.values().removeIf(prior->prior.createdAt().isBefore(clock.instant().minusSeconds(120)));
      var prior=stopped.get(input.inputId());
      if(prior!=null){if(!prior.equals(input))throw new IllegalArgumentException("Conflicting stop identity");return;}
      if(advanced.get().interruptEnabled() && input.text().strip().replaceFirst("[。.!！]+$", "").equals("実行を停止")) {
        if(stopped.size()>=4096)throw new IllegalStateException("Stop deduplication capacity reached");
        // Identity and selection are checked before any cancellation side effect.
        int count=shell.cancelSelectedActive(selected.client(),input);
        stopped.put(input.inputId(),input);
        events.publish(VoiceEventPublisher.Type.INTERRUPT_RESULT,count>0?"cancelled":"no owned active run");
        return;
      }
    }
    shell.submitSelectedVoice(selected.client(),input,() -> submitted(input));
    }
  }
  public void accept(ConversationInput input) {
    inbox.accept(input);if(inbox.confirmation())events.publish(VoiceEventPublisher.Type.REVIEW_REQUIRED,input.inputId().toString());
  }
  public void requireReview(ConversationInput input) {
    inbox.accept(input,true);events.publish(VoiceEventPublisher.Type.REVIEW_REQUIRED,input.inputId().toString());
  }
  public void setConfirmation(boolean value){inbox.setConfirmation(value);}
  public boolean confirmation(){return inbox.confirmation();}
  public List<ConversationInput> pending(){return inbox.pending();}
  public boolean cancel(UUID id){return inbox.cancel(id);}
  public void confirm(UUID id,String correction){inbox.confirm(id,correction);}
  public void clear(){inbox.clear();}
}
