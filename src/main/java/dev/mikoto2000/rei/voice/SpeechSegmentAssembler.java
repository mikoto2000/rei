package dev.mikoto2000.rei.voice;

import java.time.Clock;
import java.util.*;

/** Bounded assembly from raw VAD probabilities, distinct normal completion and maximum-time drop. */
public final class SpeechSegmentAssembler {
  public enum Reason { NONE, COMPLETED, SHORT_DROPPED, MAX_DROPPED }
  public record Decision(Reason reason,SpeechSegment segment) {}
  private static final Decision NONE=new Decision(Reason.NONE,null);
  private final VoiceSettings settings;
  private final Clock clock;
  private final float[] recent;
  private int recentCursor,recentCount;
  private float[] buffer;
  private int used,age,voiced,lastVoiced,silence;
  private boolean suppressed;
  private int suppressionSilence;
  public SpeechSegmentAssembler(VoiceSettings settings,Clock clock) {
    this.settings=Objects.requireNonNull(settings);this.clock=Objects.requireNonNull(clock);
    recent=new float[settings.samples(settings.preRollMs())];
  }
  public Decision accept(float[] frame,float probability) {
    if(frame==null||frame.length==0||frame.length>VoiceSettings.WINDOW||!Float.isFinite(probability)
        ||probability<0||probability>1)throw new IllegalArgumentException("Invalid VAD frame");
    for(float sample:frame)if(!Float.isFinite(sample)||sample < -1||sample>1)
      throw new IllegalArgumentException("Invalid PCM sample");
    try {return process(frame,probability>=settings.threshold());}
    finally {remember(frame);}
  }
  private Decision process(float[] frame,boolean speech) {
    if(suppressed) {
      suppressionSilence=speech?0:suppressionSilence+frame.length;
      if(suppressionSilence>=settings.samples(settings.silenceMs())){suppressed=false;suppressionSilence=0;}
      return NONE;
    }
    if(buffer==null) {
      if(!speech)return NONE;
      buffer=new float[recent.length+settings.samples(settings.maxSpeechMs())+VoiceSettings.WINDOW];
      int oldest=recentCount<recent.length?0:recentCursor;
      for(int i=0;i<recentCount;i++)buffer[used++]=recent[(oldest+i)%recent.length];
    }
    System.arraycopy(frame,0,buffer,used,frame.length);used+=frame.length;age+=frame.length;
    if(speech){voiced+=frame.length;lastVoiced=used;silence=0;}else silence+=frame.length;
    if(silence>=settings.samples(settings.silenceMs())) {
      if(voiced<settings.samples(settings.minSpeechMs())){discard();return new Decision(Reason.SHORT_DROPPED,null);}
      int end=Math.min(used,lastVoiced+settings.samples(settings.tailMs()));
      var segment=new SpeechSegment(UUID.randomUUID(),Arrays.copyOf(buffer,end),clock.instant());
      discard();return new Decision(Reason.COMPLETED,segment);
    }
    if(age>=settings.samples(settings.maxSpeechMs())) {
      int quiet=silence;discard();suppressed=true;suppressionSilence=quiet;
      return new Decision(Reason.MAX_DROPPED,null);
    }
    return NONE;
  }
  private void remember(float[] frame) {
    if(recent.length==0)return;
    for(float sample:frame){recent[recentCursor]=sample;recentCursor=(recentCursor+1)%recent.length;recentCount=Math.min(recent.length,recentCount+1);}
  }
  private void discard(){buffer=null;used=age=voiced=lastVoiced=silence=0;}
  /** Stopping never flushes an incomplete utterance for automatic submission. */
  public void reset(){discard();suppressed=false;suppressionSilence=recentCursor=recentCount=0;Arrays.fill(recent,0);}
}