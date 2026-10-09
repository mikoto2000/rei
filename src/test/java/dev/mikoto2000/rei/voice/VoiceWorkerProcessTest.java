package dev.mikoto2000.rei.voice;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class VoiceWorkerProcessTest {
  @org.junit.jupiter.api.io.TempDir Path temporary;
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
  @Test void interruptedCleanupWaitsForOwnedWorkerAndAllowsNextWorker() throws Exception {
    var worker=VoiceWorkerProcess.start(launch("slowexit"),Duration.ofSeconds(10),Duration.ofSeconds(3));
    Thread.currentThread().interrupt();
    try{worker.close();assertThat(worker.isAlive()).isFalse();assertThat(Thread.currentThread().isInterrupted()).isTrue();}
    finally{Thread.interrupted();worker.close();}
    try(var next=VoiceWorkerProcess.start(launch("normal"),Duration.ofSeconds(10),Duration.ofSeconds(3))) {
      assertThat(next.probability(new float[512])).isEqualTo(.75f);
    }
  }
  @Test void interruptedSecondHandshakeReleasesBothChildrenBeforeRestart() throws Exception {
    var vad=VoiceWorkerProcess.start(launch("normal"),Duration.ofSeconds(10),Duration.ofSeconds(3));
    var marker=temporary.resolve("asr.pid");var command=new ArrayList<>(launch("waithello"));command.add(marker.toString());
    var failure=new AtomicReference<Throwable>();var interruptPreserved=new AtomicBoolean();
    var startup=new Thread(()->{
      try(var ignored=VoiceWorkerProcess.start(command,Duration.ofSeconds(10),Duration.ofSeconds(3))) {
        failure.set(new AssertionError("Interrupted startup must not complete"));
      }catch(Throwable expected){failure.set(expected);}
      finally{vad.close();interruptPreserved.set(Thread.currentThread().isInterrupted());}
    });startup.setDaemon(true);startup.start();
    try {
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->java.nio.file.Files.exists(marker));
      long asrPid=Long.parseLong(java.nio.file.Files.readString(marker));startup.interrupt();startup.join(5000);
      assertThat(startup.isAlive()).isFalse();assertThat(failure.get()).isInstanceOf(IOException.class);
      assertThat(vad.isAlive()).isFalse();assertThat(ProcessHandle.of(asrPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
      assertThat(interruptPreserved).isTrue();
      try(var next=VoiceWorkerProcess.start(launch("normal"),Duration.ofSeconds(10),Duration.ofSeconds(3))) {
        assertThat(next.probability(new float[512])).isEqualTo(.75f);
      }
    }finally{startup.interrupt();startup.join(5000);vad.close();}
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
      String mode=args[0];
      if(mode.equals("slowexit"))Runtime.getRuntime().addShutdownHook(new Thread(()->{try{Thread.sleep(30000);}catch(InterruptedException ignored){}}));
      if(mode.equals("waithello")){java.nio.file.Files.writeString(Path.of(args[1]),Long.toString(ProcessHandle.current().pid()));Thread.sleep(30000);}
      var output=new DataOutputStream(System.out);var input=new DataInputStream(System.in);
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
