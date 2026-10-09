package dev.mikoto2000.rei.voice;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import javax.sound.sampled.AudioSystem;
/** Checks the factory loaded from the distributed Boot JAR. No Spring context, microphone or network. */
public final class PackagedVoiceAcceptance {
  public static void main(String[] args) throws Exception {
    if(args.length!=2)throw new IllegalArgumentException("BUNDLE SYNTHETIC_JAPANESE_WAV");
    Path bundle=Path.of(args[0]);float[] samples;
    System.out.println("PACKAGED FACTORY SOURCE: "+IsolatedVoiceBackendFactory.class.getProtectionDomain().getCodeSource().getLocation());
    try(var source=AudioSystem.getAudioInputStream(Path.of(args[1]).toFile());var pcm=AudioFormatConverter.toPcm16(source)){samples=VoicePcm.decode(pcm.readAllBytes());}
    try(var factory=new IsolatedVoiceBackendFactory(()->bundle)){
      for(int cycle=0;cycle<3;cycle++){
        var results=new ArrayList<String>();
        try(var backend=factory.open(VoiceSettings.defaults())){
          var assembler=new SpeechSegmentAssembler(VoiceSettings.defaults(),Clock.systemUTC());float[] padded=Arrays.copyOf(samples,samples.length+32000);
          for(int offset=0;offset+512<=padded.length;offset+=512){var frame=Arrays.copyOfRange(padded,offset,offset+512);
            var decision=assembler.accept(frame,backend.vad().probability(frame));if(decision.segment()!=null)results.add(backend.recognizer().recognize(decision.segment()));}
        }
        String text=String.join("",results);if(!text.contains("こんにちは")||!text.contains("音声入力")||!text.contains("日本語"))throw new IllegalStateException("Synthetic Japanese inference failed");
        if(factory.liveWorkers()!=0)throw new IllegalStateException("Owned workers remain after close");
        System.out.println("PACKAGED CYCLE "+(cycle+1)+": native Japanese VAD/ASR passed; workers=0");
      }
    }
  }
}
