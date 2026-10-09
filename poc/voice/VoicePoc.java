import com.k2fsa.sherpa.onnx.*;
import java.nio.file.*;
import java.util.*;
import javax.sound.sampled.*;

/** Isolated, offline PoC: never invokes Rei, stores a recording or contacts a model provider. */
public final class VoicePoc {
  static final AudioFormat FORMAT = new AudioFormat(16000,16,1,true,false);
  public static void main(String[] args) throws Exception {
    if(args.length==1 && args[0].equals("devices")) {
      int id=0;
      for(var info:AudioSystem.getMixerInfo()) {
        var mixer=AudioSystem.getMixer(info);
        if(Arrays.stream(mixer.getTargetLineInfo()).anyMatch(line -> TargetDataLine.class.isAssignableFrom(line.getLineClass()))) System.out.println((id++)+": "+info.getName()+" / "+info.getDescription()+" / PCM16="+mixer.isLineSupported(new DataLine.Info(TargetDataLine.class,FORMAT)));
      }
      return;
    }
    if(args.length==3 && args[0].equals("mic")) { run(Path.of(args[1]).toAbsolutePath(),capture(args[2]),0); return; }
    if(args.length!=2) throw new IllegalArgumentException("VoicePoc devices | MODEL_DIR WAV | mic MODEL_DIR EXACT_DEVICE_NAME");
    var root=Path.of(args[0]).toAbsolutePath();
    float[] samples;
    try(var source=AudioSystem.getAudioInputStream(Path.of(args[1]).toFile());var pcm=AudioSystem.getAudioInputStream(FORMAT,source)) {
      byte[] bytes=pcm.readNBytes(16000*2*60+1); if(bytes.length>16000*2*60)throw new IllegalArgumentException("PoC WAV limit: 60 seconds"); samples=Pcm.decode(bytes,bytes.length);
    }
    for(int cycle=0;cycle<3;cycle++) run(root,samples,cycle);
  }
  static float[] capture(String name) throws Exception {
    var candidates=Arrays.stream(AudioSystem.getMixerInfo()).filter(info -> info.getName().equals(name))
        .filter(info -> AudioSystem.getMixer(info).isLineSupported(new DataLine.Info(TargetDataLine.class,FORMAT))).toList();
    if(candidates.size()!=1)throw new IllegalArgumentException("Missing or ambiguous selected input device");
    var mixer=AudioSystem.getMixer(candidates.get(0));
    var line=(TargetDataLine)mixer.getLine(new DataLine.Info(TargetDataLine.class,FORMAT));
    var timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    try {
      line.open(FORMAT);
      System.out.println("Preparation: 5 seconds before capture");
      Thread.sleep(5000);line.start();
      System.out.println("CAPTURE READY: 20 seconds; repeat the test sentence; no audio file is written");

      timer.schedule(line::close,20,java.util.concurrent.TimeUnit.SECONDS);
      var output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int remaining=640000;
      while(line.isOpen() && remaining>0) {
        int n=line.read(buffer,0,Math.min(remaining,buffer.length));if(n<=0)break;
        output.write(buffer,0,n);remaining-=n;
      }
      byte[] bytes=output.toByteArray();float[] samples=Pcm.decode(bytes,bytes.length);
      double squareSum=0,peak=0;int nonzero=0;
      for(float sample:samples){squareSum+=sample*sample;peak=Math.max(peak,Math.abs(sample));if(sample!=0)nonzero++;}
      System.out.printf(Locale.ROOT,"capture samples=%d seconds=%.3f nonzero=%d peak=%.6f rms=%.6f%n",samples.length,samples.length/16000.0,nonzero,peak,samples.length==0?0:Math.sqrt(squareSum/samples.length));
      for(int start=0;start<samples.length;start+=16000){
        double binSquares=0,binPeak=0;int end=Math.min(samples.length,start+16000);
        for(int i=start;i<end;i++){binSquares+=samples[i]*samples[i];binPeak=Math.max(binPeak,Math.abs(samples[i]));}
        System.out.printf(Locale.ROOT,"capture second=%d peak=%.6f rms=%.6f%n",start/16000,binPeak,Math.sqrt(binSquares/(end-start)));
      }
      return samples;
    } finally {line.close();timer.shutdownNow();}
  }
  static void run(Path root,float[] samples,int cycle) {
    var silero=SileroVadModelConfig.builder().setModel(root.resolve("silero_vad.onnx").toString()).setThreshold(.5f).setMinSilenceDuration(1.2f).setMinSpeechDuration(.4f).setMaxSpeechDuration(25).setWindowSize(512).build();
    var vad=new Vad(VadModelConfig.builder().setSileroVadModelConfig(silero).setSampleRate(16000).setNumThreads(1).setProvider("cpu").build());
    OfflineRecognizer recognizer=null;
    try {
      var whisper=OfflineWhisperModelConfig.builder().setEncoder(root.resolve("base-encoder.int8.onnx").toString()).setDecoder(root.resolve("base-decoder.int8.onnx").toString()).setLanguage("ja").setTask("transcribe").build();
      recognizer=new OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(OfflineModelConfig.builder().setWhisper(whisper).setTokens(root.resolve("base-tokens.txt").toString()).setNumThreads(1).setProvider("cpu").build()).setDecodingMethod("greedy_search").build());
      for(int offset=0;offset<samples.length;offset+=512) vad.acceptWaveform(Arrays.copyOfRange(samples,offset,Math.min(samples.length,offset+512)));
      vad.acceptWaveform(new float[32000]);vad.flush();
      int count=0;
      while(!vad.empty()) {
        var segment=vad.front();var stream=recognizer.createStream();
        try {
          stream.acceptWaveform(segment.getSamples(),16000);long start=System.nanoTime();recognizer.decode(stream);
          var text=recognizer.getResult(stream).getText();
          if(text.isBlank()) throw new AssertionError("Empty recognized segment");
          System.out.printf(Locale.ROOT,"cycle=%d segment=%d seconds=%.3f decodeMs=%.1f text=%s%n",cycle,++count,segment.getSamples().length/16000.0,(System.nanoTime()-start)/1e6,text);
        } finally {stream.release();}
        vad.pop();
      }
      if(count==0)throw new AssertionError("VAD produced no speech");
    } finally {if(recognizer!=null)recognizer.release();vad.release();}
  }
}
