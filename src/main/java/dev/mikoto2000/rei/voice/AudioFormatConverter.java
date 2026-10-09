package dev.mikoto2000.rei.voice;

import javax.sound.sampled.*;

public final class AudioFormatConverter {
  public static final AudioFormat PCM16=new AudioFormat(16000,16,1,true,false);
  private AudioFormatConverter(){}
  public static AudioInputStream toPcm16(AudioInputStream input){
    if(input.getFormat().matches(PCM16))return input;
    if(!AudioSystem.isConversionSupported(PCM16,input.getFormat()))
      throw new IllegalArgumentException("Selected audio format cannot be converted to 16kHz mono PCM16");
    return AudioSystem.getAudioInputStream(PCM16,input);
  }
}