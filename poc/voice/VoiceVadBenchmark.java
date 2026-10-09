package dev.mikoto2000.rei.voice;
import java.nio.file.*;import java.time.*;import java.util.*;import javax.sound.sampled.AudioSystem;
/** Raw VAD/sample-clock grid; candidates are not ASR results or delivered inputs. */
public final class VoiceVadBenchmark {
  public static void main(String[] args)throws Exception{
    if(args.length!=3)throw new IllegalArgumentException("BUNDLE FIXTURES OUTPUT");
    Path root=Path.of(args[0]),fixtures=Path.of(args[1]);
    try(var factory=new SherpaBackendFactory(()->root);var writer=Files.newBufferedWriter(Path.of(args[2]))){
      writer.write("file\tthreshold\tsilenceMs\tcandidates\tdropped\tfinalVadWaitMs\n");
      for(String line:Files.readAllLines(fixtures.resolve("fixtures.tsv"))){if(line.isBlank())continue;String file=line.split("\t",2)[0];if(!file.matches("[A-Za-z0-9._-]+"))throw new IllegalArgumentException("Unsafe fixture");float[] samples;
        try(var source=AudioSystem.getAudioInputStream(fixtures.resolve(file).toFile());var pcm=AudioFormatConverter.toPcm16(source)){samples=VoicePcm.decode(pcm.readAllBytes());}
        float[] padded=Arrays.copyOf(samples,samples.length+48000);int count=padded.length/512;float[] probabilities=new float[count];
        // New native VAD per fixture: independent recurrent state; no ASR in this process.
        try(var vadOnly=factory.openVad(VoiceSettings.defaults())){for(int i=0;i<count;i++)probabilities[i]=vadOnly.vad().probability(Arrays.copyOfRange(padded,i*512,(i+1)*512));}
        for(float threshold:new float[]{.4f,.5f,.65f})for(int silence:new int[]{600,1200,1800,2400}){
          var assembler=new SpeechSegmentAssembler(new VoiceSettings(threshold,300,400,silence,25000,200),Clock.systemUTC());int candidates=0,dropped=0,lastSpeech=0;double wait=Double.NaN;
          for(int i=0;i<count;i++){if(probabilities[i]>=threshold)lastSpeech=(i+1)*512;
            var decision=assembler.accept(Arrays.copyOfRange(padded,i*512,(i+1)*512),probabilities[i]);
            if(decision.segment()!=null){candidates++;wait=((i+1)*512-lastSpeech)/16.0;}
            if(decision.reason()==SpeechSegmentAssembler.Reason.SHORT_DROPPED||decision.reason()==SpeechSegmentAssembler.Reason.MAX_DROPPED)dropped++;
          }
          writer.write(file+"\t"+threshold+"\t"+silence+"\t"+candidates+"\t"+dropped+"\t"+wait+"\n");
        }
        writer.flush();System.out.println("VAD GRID "+file);
      }
    }
  }
}