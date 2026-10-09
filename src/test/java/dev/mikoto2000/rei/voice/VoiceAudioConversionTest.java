package dev.mikoto2000.rei.voice;

import java.io.*;
import javax.sound.sampled.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VoiceAudioConversionTest {
  @Test void signedPcm16AndShortReadsDecodeWithoutStaleBytes() {
    var result=VoicePcm.decode(new byte[]{(byte)255,0,0,(byte)128,1,2},4);
    assertThat(result).containsExactly(255f/32768,-1f);
    assertThatThrownBy(()->VoicePcm.decode(new byte[4],3)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void stereo48kIsConvertedTo16kMonoBeforeVad() throws Exception {
    byte[] source=new byte[600*4];
    for(int i=0;i<600;i++){source[4*i+1]=64;source[4*i+3]=64;}
    var format=new AudioFormat(48000,16,2,true,false);
    try(var input=new AudioInputStream(new ByteArrayInputStream(source),format,600);
        var converted=AudioFormatConverter.toPcm16(input)){
      assertThat(converted.getFormat().getSampleRate()).isEqualTo(16000f);
      assertThat(converted.getFormat().getChannels()).isEqualTo(1);
      var samples=VoicePcm.decode(converted.readAllBytes());
      assertThat(samples.length).isBetween(190,210);
      assertThat(samples[samples.length/2]).isCloseTo(.5f,within(.01f));
    }
  }
  @Test void unsignedAndBigEndianFormatsAreConvertedRatherThanMisdecoded() throws Exception {
    byte[] bytes={0x40,0,0x40,0};
    try(var input=new AudioInputStream(new ByteArrayInputStream(bytes),new AudioFormat(16000,16,1,true,true),2);
        var converted=AudioFormatConverter.toPcm16(input)){
      assertThat(VoicePcm.decode(converted.readAllBytes())).containsExactly(.5f,.5f);
    }
  }
}