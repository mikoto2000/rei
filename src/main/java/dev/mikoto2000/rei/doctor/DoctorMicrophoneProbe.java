package dev.mikoto2000.rei.doctor;

import dev.mikoto2000.rei.voice.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** One bounded outstanding acquisition; timeout never starts another blocked native driver. */
@Component
public class DoctorMicrophoneProbe implements AutoCloseable {
  public enum Result { AVAILABLE, NOT_CONFIGURED, UNAVAILABLE, TIMEOUT, BUSY }
  @FunctionalInterface public interface Acquisition {void acquire(AudioDevice device) throws Exception;}
  private final AudioDeviceService devices;
  private final Acquisition acquisition;
  private final Semaphore available=new Semaphore(1);
  private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("doctor-microphone").factory());
  @org.springframework.beans.factory.annotation.Autowired
  public DoctorMicrophoneProbe(AudioDeviceService devices) {this(devices,new JavaSoundMicrophoneCapture()::acquireWithoutRecording);}
  public DoctorMicrophoneProbe(AudioDeviceService devices, Acquisition acquisition) {this.devices=devices;this.acquisition=acquisition;}
  public Result check(String deviceId, Duration timeout, Runnable checkActive) {
    checkActive.run();
    if(timeout==null||timeout.compareTo(Duration.ofMillis(100))<0||timeout.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("Microphone timeout must be 100ms to 30s");
    if(deviceId==null||deviceId.isBlank())return Result.NOT_CONFIGURED;
    if(!available.tryAcquire())return Result.BUSY;
    var started=new AtomicBoolean();var released=new AtomicBoolean();var stop=new AtomicBoolean();
    Runnable release=()->{if(released.compareAndSet(false,true))available.release();};
    Future<Result> pending;
    try {pending=worker.submit(()->{
      started.set(true);
      try {
        if(stop.get()||Thread.currentThread().isInterrupted())return Result.UNAVAILABLE;
        var matches=devices.devices().stream().filter(d->d.id().equals(deviceId)).toList();
        if(matches.size()!=1)return Result.UNAVAILABLE;
        if(stop.get()||Thread.currentThread().isInterrupted())return Result.UNAVAILABLE;
        acquisition.acquire(matches.getFirst());
        return Result.AVAILABLE;
      } finally {release.run();}
    });}catch(RuntimeException rejected){release.run();return Result.UNAVAILABLE;}
    long deadline=System.nanoTime()+timeout.toNanos();
    try {
      while(true) {
        checkActive.run();long remaining=deadline-System.nanoTime();
        if(remaining<=0)return Result.TIMEOUT;
        try {var result=pending.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(50)),TimeUnit.NANOSECONDS);checkActive.run();return result;}
        catch(TimeoutException slice) { }
      }
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("Microphone diagnosis cancelled");}
    catch(ExecutionException unavailable){return Result.UNAVAILABLE;}
    finally {stop.set(true);pending.cancel(true);if(!started.get())release.run();}
  }
  @Override @jakarta.annotation.PreDestroy public void close(){worker.shutdownNow();}
}
