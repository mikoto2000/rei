package dev.mikoto2000.rei.voice;

import java.time.Instant;
import java.util.*;

public record SpeechSegment(UUID id,float[] samples,Instant createdAt) {
  public SpeechSegment {
    Objects.requireNonNull(id);Objects.requireNonNull(createdAt);
    if(samples==null||samples.length==0||samples.length>VoiceSettings.SAMPLE_RATE*28)
      throw new IllegalArgumentException("Invalid speech segment size");
    samples=samples.clone();
  }
  @Override public float[] samples(){return samples.clone();}
}