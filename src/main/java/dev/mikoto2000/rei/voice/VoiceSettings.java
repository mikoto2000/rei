package dev.mikoto2000.rei.voice;

/** Sample-count timing at 16kHz; no second wall-clock silence timer. */
public record VoiceSettings(float threshold,int preRollMs,int minSpeechMs,int silenceMs,int maxSpeechMs,int tailMs) {
  public static final int SAMPLE_RATE=16000, WINDOW=512;
  public VoiceSettings {
    if(!Float.isFinite(threshold)||threshold<=0||threshold>1||preRollMs<0||preRollMs>2000
        ||minSpeechMs<1||silenceMs<1||silenceMs>10000||maxSpeechMs<minSpeechMs||maxSpeechMs>25000
        ||tailMs<0||tailMs>silenceMs)throw new IllegalArgumentException("Invalid voice settings");
  }
  public static VoiceSettings defaults(){return new VoiceSettings(.5f,300,400,1800,25000,200);}
  public int samples(int milliseconds){return milliseconds*16;}
}