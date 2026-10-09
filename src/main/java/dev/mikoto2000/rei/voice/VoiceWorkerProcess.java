package dev.mikoto2000.rei.voice;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Bounded binary IPC. Native output never reaches the parent terminal; only this owned child is stopped. */
public final class VoiceWorkerProcess implements AutoCloseable {
  public static final int MAGIC=0x52454956,VERSION=1,MAX_SAMPLES=448000,MAX_TEXT_BYTES=65536;
  private final Process process;
  private final DataInputStream input;
  private final DataOutputStream output;
  private final Duration requestTimeout;
  private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"voice-worker-ipc");t.setDaemon(true);return t;});
  private final AtomicBoolean closed=new AtomicBoolean();
  private final AtomicLong stderrBytes=new AtomicLong();
  private final Thread stderr;
  private volatile boolean busy;
  private VoiceWorkerProcess(Process process,Duration requestTimeout) {
    this.process=process;this.requestTimeout=requestTimeout;
    input=new DataInputStream(new BufferedInputStream(process.getInputStream(),65536));
    output=new DataOutputStream(new BufferedOutputStream(process.getOutputStream(),65536));
    stderr=new Thread(()->{try(var stream=process.getErrorStream()){
      byte[] buffer=new byte[8192];int size;while((size=stream.read(buffer))!=-1)stderrBytes.addAndGet(size);
    }catch(IOException ignored){}},"voice-worker-stderr");stderr.setDaemon(true);stderr.start();
  }
  public static VoiceWorkerProcess start(List<String> command,Duration startupTimeout,Duration requestTimeout) throws IOException {
    Objects.requireNonNull(command);if(command.isEmpty()||startupTimeout.isZero()||startupTimeout.isNegative()||requestTimeout.isZero()||requestTimeout.isNegative())throw new IllegalArgumentException("Invalid worker command or deadline");
    var worker=new VoiceWorkerProcess(new ProcessBuilder(List.copyOf(command)).start(),requestTimeout);
    worker.exchange(()->{if(worker.input.readInt()!=MAGIC||worker.input.readInt()!=VERSION)throw new IOException("Invalid voice worker handshake");return null;},startupTimeout);
    return worker;
  }
  public synchronized float probability(float[] frame) throws IOException {
    VoicePcm.validateFrame(frame);float[] copy=frame.clone();
    return exchange(()->{write(1,copy);if(input.readInt()!=1)throw new IOException("Invalid VAD response");float value=input.readFloat();
      if(!Float.isFinite(value)||value<0||value>1)throw new IOException("Invalid VAD probability");return value;},requestTimeout);
  }
  public synchronized String recognize(float[] samples) throws IOException {
    if(samples==null||samples.length<1||samples.length>MAX_SAMPLES)throw new IllegalArgumentException("Invalid worker sample count");
    float[] copy=samples.clone();for(float sample:copy)if(!Float.isFinite(sample)||Math.abs(sample)>1)throw new IllegalArgumentException("Invalid worker PCM");
    return exchange(()->{write(2,copy);if(input.readInt()!=2)throw new IOException("Invalid ASR response");int size=input.readInt();
      if(size<0||size>MAX_TEXT_BYTES)throw new IOException("Invalid ASR response size");byte[] bytes=new byte[size];input.readFully(bytes);
      return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();},requestTimeout);
  }
  private void write(int operation,float[] samples) throws IOException {
    output.writeInt(operation);output.writeInt(samples.length);for(float sample:samples)output.writeFloat(sample);output.flush();
  }
  private <T>T exchange(Callable<T> action,Duration timeout) throws IOException {
    if(closed.get())throw new IOException("Voice worker is closed");busy=true;
    Future<T> task=null;
    try{task=io.submit(action);return task.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
    catch(InterruptedException interrupted){close();Thread.currentThread().interrupt();throw new IOException("Voice worker request interrupted");}
    catch(ExecutionException|TimeoutException|RejectedExecutionException failure){close();throw new IOException("Voice worker failed or exceeded its deadline");}
    finally{if(task!=null&&!task.isDone())task.cancel(true);busy=false;}
  }
  public long stderrBytes(){return stderrBytes.get();}
  public boolean isAlive(){return process.isAlive();}
  public void close() {
    if(!closed.compareAndSet(false,true))return;
    boolean interrupted=Thread.interrupted();
    try {
      if(!busy)try{output.close();}catch(IOException ignored){}else process.destroy();
      try{if(!process.waitFor(1,TimeUnit.SECONDS))process.destroyForcibly();}
      catch(InterruptedException stop){interrupted=true;process.destroyForcibly();}
      // destroyForcibly is asynchronous. Wait within one monotonic budget even when
      // cancellation has already interrupted this caller, then restore its flag.
      if(process.isAlive()) {
        process.destroyForcibly();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(process.isAlive()) {
          long remaining=deadline-System.nanoTime();if(remaining<=0)break;
          try{process.waitFor(remaining,TimeUnit.NANOSECONDS);}catch(InterruptedException stop){interrupted=true;}
        }
      }
    }finally{
      process.destroyForcibly();io.shutdownNow();
      try{input.close();}catch(IOException ignored){}
      try{output.close();}catch(IOException ignored){}
      try{stderr.join(250);}catch(InterruptedException stop){interrupted=true;}
      if(interrupted)Thread.currentThread().interrupt();
    }
  }
}
