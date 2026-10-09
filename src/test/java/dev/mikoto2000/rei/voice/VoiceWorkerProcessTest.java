package dev.mikoto2000.rei.voice;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class VoiceWorkerProcessTest {
  List<String> launch(String mode) throws Exception{return List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",Path.of(FakeWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),FakeWorker.class.getName(),mode);}
  @Test void boundedProtocolDrainsNativeStderrWithoutWritingItToTheTerminal() throws Exception {
    try(var worker=VoiceWorkerProcess.start(launch("normal"),Duration.ofSeconds(10),Duration.ofSeconds(3))){
      assertThat(worker.probability(new float[512])).isEqualTo(.75f);
      assertThat(worker.recognize(new float[1024])).isEqualTo("こんにちは");
      assertThat(worker.stderrBytes()).isGreaterThan(1000000);
    }
  }
  @Test void crashedTimedOutOrInvalidWorkerIsStoppedWithoutCrashingTheCaller() throws Exception {
    for(String mode:List.of("crash","stall","invalid")){
      var worker=VoiceWorkerProcess.start(launch(mode),Duration.ofSeconds(10),Duration.ofMillis(300));
      assertThatThrownBy(()->worker.probability(new float[512])).isInstanceOf(IOException.class);
      assertThat(worker.isAlive()).isFalse();worker.close();
    }
    assertThatThrownBy(()->VoiceWorkerProcess.start(launch("badhello"),Duration.ofSeconds(10),Duration.ofSeconds(1))).isInstanceOf(IOException.class);
  }
  @Test void oversizedAndNonfiniteInputsNeverReachWorker() throws Exception {
    try(var worker=VoiceWorkerProcess.start(launch("normal"),Duration.ofSeconds(10),Duration.ofSeconds(3))){
      assertThatThrownBy(()->worker.recognize(new float[448001])).isInstanceOf(IllegalArgumentException.class);
      var frame=new float[512];frame[0]=Float.NaN;
      assertThatThrownBy(()->worker.probability(frame)).isInstanceOf(IllegalArgumentException.class);
      assertThat(worker.probability(new float[512])).isEqualTo(.75f);
    }
  }
  public static class FakeWorker {
    public static void main(String[] args) throws Exception {
      String mode=args[0];var output=new DataOutputStream(System.out);var input=new DataInputStream(System.in);
      if(mode.equals("normal")){var noise=new byte[8192];Arrays.fill(noise,(byte)'x');for(int i=0;i<256;i++)System.err.write(noise);System.err.flush();}
      output.writeInt(mode.equals("badhello")?0:0x52454956);output.writeInt(1);output.flush();
      while(true){int operation;try{operation=input.readInt();}catch(EOFException done){return;}
        int size=input.readInt();for(int i=0;i<size;i++)input.readFloat();
        if(mode.equals("crash")){Runtime.getRuntime().halt(7);return;}
        if(mode.equals("stall"))Thread.sleep(30000);
        output.writeInt(operation);
        if(operation==1)output.writeFloat(mode.equals("invalid")?Float.NaN:.75f);
        else{byte[] text="こんにちは".getBytes(java.nio.charset.StandardCharsets.UTF_8);output.writeInt(text.length);output.write(text);}
        output.flush();
      }
    }
  }
}
