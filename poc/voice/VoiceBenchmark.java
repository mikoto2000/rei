package dev.mikoto2000.rei.voice;
import com.k2fsa.sherpa.onnx.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sound.sampled.*;
/** Offline benchmark only. No microphone, Spring, Agent, network or production model switch. */
public final class VoiceBenchmark {
  static final Base64.Encoder B64=Base64.getEncoder();
  static String encode(String s){return B64.encodeToString(s.getBytes(StandardCharsets.UTF_8));}
  static long cpu(){return ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();}
  public static void main(String[] args) throws Exception {
    if(args.length!=7)throw new IllegalArgumentException("MODEL_DIR tiny|base-int8|base-fp32|small-int8|small-fp32|turbo FIXTURES OUTPUT THREADS TAIL_PADDING SILENCE_MS");
    Path models=Path.of(args[0]),fixtures=Path.of(args[2]),output=Path.of(args[3]);
    String profile=args[1];boolean int8=profile.endsWith("-int8");String name=profile.split("-")[0];
    if(!Set.of("tiny","base-int8","base-fp32","small-int8","small-fp32","turbo").contains(profile))throw new IllegalArgumentException("Unknown comparison profile");
    int threads=Integer.parseInt(args[4]),tail=Integer.parseInt(args[5]),silence=Integer.parseInt(args[6]);
    if(threads<1||threads>4||!Set.of(250,500,1000).contains(tail))throw new IllegalArgumentException("Unsupported benchmark settings");
    var settings=new VoiceSettings(.5f,300,400,silence,25000,200);
    String suffix=int8?".int8.onnx":".onnx";
    var whisper=OfflineWhisperModelConfig.builder().setEncoder(models.resolve(name+"-encoder"+suffix).toString())
        .setDecoder(models.resolve(name+"-decoder"+suffix).toString()).setLanguage("ja").setTask("transcribe").setTailPaddings(tail).build();
    try(var vocabulary=WhisperByteVocabulary.create(models.resolve(name+"-tokens.txt"))) {
    long startup=System.nanoTime();
    var recognizer=new OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(OfflineModelConfig.builder().setWhisper(whisper)
        .setTokens(vocabulary.path().toString()).setNumThreads(threads).setProvider("cpu").setDebug(false).build()).setDecodingMethod("greedy_search").build());
    double loadMs=(System.nanoTime()-startup)/1e6;
    try {
    var vad=new Vad(VadModelConfig.builder().setSileroVadModelConfig(SileroVadModelConfig.builder().setModel(models.resolve("silero_vad.onnx").toString())
        .setThreshold(.5f).setWindowSize(512).setMinSilenceDuration(silence/1000f).setMinSpeechDuration(.4f).setMaxSpeechDuration(25).build())
        .setSampleRate(16000).setNumThreads(1).setProvider("cpu").setDebug(false).build());
    System.out.println("BENCHMARK PID="+ProcessHandle.current().pid()+" profile="+profile+" loadMs="+loadMs);
    try(var writer=Files.newBufferedWriter(output,StandardCharsets.UTF_8)){
      writer.write("profile\tfile\treference64\thypothesis64\taudioSeconds\tasrMs\tcpuMs\tcer\tsegments\tdropped\tpipelineHypothesis64\tendToAdmissionMs\tloadMs\tthreads\ttailPadding\tsilenceMs\tbyteSafe\n");
      for(String line:Files.readAllLines(fixtures.resolve("fixtures.tsv"),StandardCharsets.UTF_8)){
        if(line.isBlank())continue;String[] fields=line.split("\t",2);String file=fields[0],reference=fields.length==2?fields[1]:"";
        Path wave=fixtures.resolve(file).normalize();if(!wave.startsWith(fixtures.normalize())||!file.matches("[A-Za-z0-9._-]+"))throw new IllegalArgumentException("Unsafe fixture");
        float[] samples;try(var source=AudioSystem.getAudioInputStream(wave.toFile());var pcm=AudioFormatConverter.toPcm16(source)){samples=VoicePcm.decode(pcm.readAllBytes());}
        if(samples.length>448000)throw new IllegalArgumentException("Fixture exceeds input limit");
        long c=cpu(),begin=System.nanoTime();String text=recognize(recognizer,samples);double decodeMs=(System.nanoTime()-begin)/1e6,cpuMs=(cpu()-c)/1e6;
        vad.reset();var assembler=new SpeechSegmentAssembler(settings,Clock.systemUTC());var texts=new ArrayList<String>();int drops=0,lastSpeechEnd=0;double admission=Double.NaN;
        float[] padded=Arrays.copyOf(samples,samples.length+settings.samples(silence+1000));
        // Offline sample-clock replay: silence delay plus measured recognition/filter latency; excludes Agent/network.
        for(int offset=0;offset+512<=padded.length;offset+=512){float[] frame=Arrays.copyOfRange(padded,offset,offset+512);
          float probability=vad.compute(frame);if(probability>=settings.threshold())lastSpeechEnd=offset+512;
          var decision=assembler.accept(frame,probability);
          if(decision.reason()==SpeechSegmentAssembler.Reason.MAX_DROPPED||decision.reason()==SpeechSegmentAssembler.Reason.SHORT_DROPPED)drops++;
          if(decision.segment()!=null){long decodeStart=System.nanoTime();String result=recognize(recognizer,decision.segment().samples());
            var accepted=SpeechResultFilter.filter(result);if(accepted.isPresent())texts.add(accepted.get());
            if(accepted.isPresent())admission=(offset+512-lastSpeechEnd)/16.0+(System.nanoTime()-decodeStart)/1e6;}
        }
        double cer=VoiceBenchmarkMetrics.normalize(reference).isEmpty()?Double.NaN:VoiceBenchmarkMetrics.cer(reference,text);
        writer.write(String.join("\t",profile,file,encode(reference),encode(text),Double.toString(samples.length/16000.0),Double.toString(decodeMs),Double.toString(cpuMs),Double.toString(cer),Integer.toString(texts.size()),Integer.toString(drops),encode(String.join("",texts)),Double.toString(admission),Double.toString(loadMs),Integer.toString(threads),Integer.toString(tail),Integer.toString(silence),"true")+"\n");writer.flush();
        System.out.println("FIXTURE "+file+" asrMs="+decodeMs+" CER="+cer+" segments="+texts.size());
      }
    }finally{vad.release();}
    } finally {recognizer.release();}
    }
  }
  static String recognize(OfflineRecognizer recognizer,float[] samples) throws Exception {var stream=recognizer.createStream();try{stream.acceptWaveform(samples,16000);recognizer.decode(stream);return WhisperByteVocabulary.decode(recognizer.getResult(stream).getText());}finally{stream.release();}}
}