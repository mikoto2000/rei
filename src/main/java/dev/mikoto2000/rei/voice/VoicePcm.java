package dev.mikoto2000.rei.voice;

import java.util.Objects;

public final class VoicePcm {
  /** Reject malformed frames before any native VAD call. */
  public static void validateFrame(float[] frame) {
    if(frame==null||frame.length!=VoiceSettings.WINDOW)throw new IllegalArgumentException("Expected 512 PCM samples");
    for(float sample:frame)if(!Float.isFinite(sample)||sample < -1||sample > 1)
      throw new IllegalArgumentException("Invalid PCM sample");
  }
  private VoicePcm(){}
  public static float[] decode(byte[] bytes){return decode(bytes,bytes.length);}
  public static float[] decode(byte[] bytes,int count){
    Objects.requireNonNull(bytes);
    if(count<0||count>bytes.length||(count&1)!=0)throw new IllegalArgumentException("Invalid PCM16 length");
    float[] samples=new float[count/2];
    for(int i=0;i<samples.length;i++)samples[i]=(short)((bytes[2*i]&255)|(bytes[2*i+1]<<8))/32768f;
    return samples;
  }
}