package dev.mikoto2000.rei.voice;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
/** Bounded optional narration of the captured Shell owner's completed VOICE replies. */
public final class VoicePlaybackService implements AutoCloseable {
  private final VoiceSpeechOutput output;
  private final VoiceAudioGate gate;
  private final Supplier<VoiceAdvancedOptions> options;
  private final Predicate<AgentRunContext> selected;
  private final VoiceEventPublisher events;
  private final AtomicLong generation=new AtomicLong();
  private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,"voice-playback");t.setDaemon(true);return t;});
  public VoicePlaybackService(VoiceSpeechOutput output,VoiceAudioGate gate,
      Supplier<VoiceAdvancedOptions> options,Predicate<AgentRunContext> selected,VoiceEventPublisher events) {
    this.output=Objects.requireNonNull(output);this.gate=Objects.requireNonNull(gate);
    this.options=Objects.requireNonNull(options);this.selected=Objects.requireNonNull(selected);this.events=Objects.requireNonNull(events);
  }
  public boolean enabledFor(AgentRunContext context){
    return context!=null && context.voiceInput() && context.requestSource()==AgentRunContext.RequestSource.SHELL
        && options.get().ttsEnabled();
  }
  private boolean eligible(AgentRunContext context){
    return enabledFor(context) && selected.test(context) && !worker.isShutdown();
  }
  /** True means this opt-in handled the reply, including an explicit bounded drop. */
  public boolean offer(AgentRunContext context,String text) {
    if(!eligible(context)||text==null||text.isBlank())return false;
    if(text.length()>16384){events.publish(VoiceEventPublisher.Type.TTS_DROPPED,"reply too long; see displayed response");return true;}
    long epoch=generation.get();
    try {worker.execute(()->{
      BooleanSupplier current=()->epoch==generation.get()&&eligible(context);
      if(!current.getAsBoolean())return;
      var settings=options.get();
      try(var playback=gate.playback(Duration.ofMillis(settings.echoTailMs()))) {
        if(current.getAsBoolean())output.speak(settings.ttsVoice(),text,current);
      } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
      catch(Exception failure){events.publish(VoiceEventPublisher.Type.TTS_FAILED,"local playback failed");}
    });} catch(RejectedExecutionException full){events.publish(VoiceEventPublisher.Type.TTS_DROPPED,"playback queue full");}
    return true;
  }
  public void stop(){generation.incrementAndGet();worker.getQueue().clear();output.stop();}
  @Override public void close(){stop();worker.shutdownNow();try{worker.awaitTermination(3,TimeUnit.SECONDS);}
    catch(InterruptedException e){Thread.currentThread().interrupt();}output.close();}
}
