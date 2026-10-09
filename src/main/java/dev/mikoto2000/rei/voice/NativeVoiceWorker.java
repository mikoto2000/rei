package dev.mikoto2000.rei.voice;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
/** Dedicated inference entry point: no Spring, scheduler, Agent, microphone, network or audio file. */
public final class NativeVoiceWorker {
  public static void main(String[] args) {
    try{run(args);}catch(Throwable failure){System.err.println("VOICE_WORKER_FAILED");System.exit(2);}
  }
  private static void run(String[] args) throws Exception {
    if((args.length!=8&&args.length!=10)||(!args[0].equals("vad")&&!args[0].equals("asr")))throw new IllegalArgumentException("Invalid worker options");
    boolean vad=args[0].equals("vad");Path root=Path.of(args[1]);
    var settings=new VoiceSettings(Float.parseFloat(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]),Integer.parseInt(args[5]),Integer.parseInt(args[6]),Integer.parseInt(args[7]));
    var options=args.length==10?new VoiceInferenceOptions(Integer.parseInt(args[8]),Integer.parseInt(args[9])):VoiceInferenceOptions.defaults();
    try(var factory=new SherpaBackendFactory(()->root,()->options);var backend=vad?factory.openVad(settings):factory.openRecognizer(settings)) {
      var input=new DataInputStream(new BufferedInputStream(System.in,65536));var output=new DataOutputStream(new BufferedOutputStream(System.out,65536));
      output.writeInt(VoiceWorkerProcess.MAGIC);output.writeInt(VoiceWorkerProcess.VERSION);output.flush();
      while(true){int operation;try{operation=input.readInt();}catch(EOFException stopped){return;}
        if(operation!=(vad?1:2))throw new IOException("Invalid worker role operation");
        int size=input.readInt();if(size<1||size>VoiceWorkerProcess.MAX_SAMPLES||(vad&&size!=512))throw new IOException("Invalid worker sample count");
        float[] samples=new float[size];for(int i=0;i<size;i++){float value=input.readFloat();if(!Float.isFinite(value)||Math.abs(value)>1)throw new IOException("Invalid PCM");samples[i]=value;}
        if(vad){float probability=backend.vad().probability(samples);output.writeInt(operation);output.writeFloat(probability);}
        else{String result=backend.recognizer().recognize(new SpeechSegment(UUID.randomUUID(),samples,Instant.now()));
          byte[] bytes=result.getBytes(StandardCharsets.UTF_8);if(bytes.length>VoiceWorkerProcess.MAX_TEXT_BYTES)throw new IOException("Recognition exceeds response bound");
          output.writeInt(operation);output.writeInt(bytes.length);output.write(bytes);}
        output.flush();
      }
    }
  }
}
