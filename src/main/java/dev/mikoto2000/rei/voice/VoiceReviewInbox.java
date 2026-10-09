package dev.mikoto2000.rei.voice;

import java.time.*;
import java.util.*;
import java.util.function.*;
import dev.mikoto2000.rei.application.input.*;

/** Recognition confirmation is separate from tool approval. Pending speech stays only in bounded memory. */
public final class VoiceReviewInbox {
  public static final int CAPACITY=3;
  public static final Duration RETENTION=Duration.ofMinutes(2);
  private final Consumer<ConversationInput> dispatch;
  private final Predicate<ConversationInput> selected;
  private final Clock clock;
  private final Map<UUID,ConversationInput> pending=new LinkedHashMap<>();
  private boolean confirmation;
  public VoiceReviewInbox(Consumer<ConversationInput> dispatch,Predicate<ConversationInput> selected,Clock clock) {
    this.dispatch=Objects.requireNonNull(dispatch);this.selected=Objects.requireNonNull(selected);this.clock=Objects.requireNonNull(clock);
  }
  public synchronized void setConfirmation(boolean value){confirmation=value;}
  public synchronized boolean confirmation(){return confirmation;}
  private boolean valid(ConversationInput input) {
    var now=clock.instant();return !input.createdAt().isAfter(now)&&!input.createdAt().isBefore(now.minus(RETENTION))&&selected.test(input);
  }
  private void prune(){pending.values().removeIf(input->!valid(input));}
  public synchronized void accept(ConversationInput input) {
    if(input.source()!=InputSource.VOICE)throw new IllegalArgumentException("Recognition inbox accepts VOICE only");
    prune();if(!valid(input))throw new IllegalStateException("Voice target changed or recognition expired");
    if(!confirmation){dispatch.accept(input);return;}
    var previous=pending.get(input.inputId());
    if(previous!=null){if(!previous.equals(input))throw new IllegalArgumentException("Conflicting recognition identity");return;}
    if(pending.size()>=CAPACITY)throw new IllegalStateException("Recognition confirmation queue is full (3)");
    pending.put(input.inputId(),input);
  }
  public synchronized List<ConversationInput> pending(){prune();return List.copyOf(pending.values());}
  public synchronized boolean cancel(UUID id){prune();return pending.remove(id)!=null;}
  public synchronized void clear(){pending.clear();}
  public synchronized void confirm(UUID id,String correction) {
    prune();var input=pending.get(id);
    if(input==null)throw new IllegalArgumentException("Recognition is missing, cancelled, expired or its target changed");
    String text=correction==null?input.text():SpeechResultFilter.filter(correction)
      .orElseThrow(()->new IllegalArgumentException("Invalid voice correction; commands are not allowed"));
    var corrected=new ConversationInput(input.inputId(),InputSource.VOICE,input.target(),text,input.createdAt());
    pending.put(id,corrected);dispatch.accept(corrected);pending.remove(id);
  }
}