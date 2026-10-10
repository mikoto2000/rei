package dev.mikoto2000.rei.doctor;
import dev.mikoto2000.rei.voice.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DoctorMicrophoneProbeTest {
  final AudioDevice device = new AudioDevice("id","private name","description","vendor","1");
  @Test void onlyExplicitDeviceIsAcquiredAndEmptyConfigurationDoesNotEnumerate() {
    var acquired = new java.util.concurrent.atomic.AtomicInteger();
    var catalog = new java.util.concurrent.atomic.AtomicInteger();
    try (var probe = new DoctorMicrophoneProbe(new AudioDeviceService(() -> {catalog.incrementAndGet(); return List.of(device);}), d -> acquired.incrementAndGet())) {
      assertEquals(DoctorMicrophoneProbe.Result.NOT_CONFIGURED,probe.check(null,Duration.ofMillis(100),()->{}));
      assertEquals(0,catalog.get());
      assertEquals(DoctorMicrophoneProbe.Result.AVAILABLE,probe.check("id",Duration.ofSeconds(1),()->{}));
      assertEquals(1,acquired.get());
      assertEquals(DoctorMicrophoneProbe.Result.UNAVAILABLE,probe.check("other",Duration.ofSeconds(1),()->{}));
      assertEquals(1,acquired.get());
    }
  }
  @Test void uninterruptibleDriverCannotCreateUnboundedAcquisitions() throws Exception {
    var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
    try (var probe=new DoctorMicrophoneProbe(new AudioDeviceService(()->List.of(device)), d->{
      entered.countDown(); boolean done=false;
      while(!done) try {release.await();done=true;} catch(InterruptedException ignored) {}
    })) {
      assertEquals(DoctorMicrophoneProbe.Result.TIMEOUT,probe.check("id",Duration.ofMillis(100),()->{}));
      assertTrue(entered.await(1,TimeUnit.SECONDS));
      assertEquals(DoctorMicrophoneProbe.Result.BUSY,probe.check("id",Duration.ofMillis(100),()->{}));
    } finally {release.countDown();}
  }
  @Test void cancellationPropagatesBeforeAcquisition() {
    try (var probe=new DoctorMicrophoneProbe(new AudioDeviceService(()->{throw new AssertionError();}),d->{throw new AssertionError();})) {
      assertThrows(CancellationException.class,()->probe.check("id",Duration.ofSeconds(1),()->{throw new CancellationException();}));
    }
  }
}
