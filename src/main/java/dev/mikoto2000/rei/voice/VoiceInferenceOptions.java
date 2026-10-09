package dev.mikoto2000.rei.voice;
/** Bounded CPU inference tuning, independent of VAD sample timing and model identity. */
public record VoiceInferenceOptions(int threads,int tailFrames) {
  public VoiceInferenceOptions {
    if(threads<1||threads>4||tailFrames<250||tailFrames>2000)
      throw new IllegalArgumentException("ASR threads must be 1..4; tail frames must be 250..2000");
  }
  public static VoiceInferenceOptions defaults(){return new VoiceInferenceOptions(4,1000);}
}
