package dev.mikoto2000.rei.activity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

class WindowsInputProbeTest {
  @Test @EnabledOnOs(OS.WINDOWS) void nativeReadDoesNotRequirePowerShellOrCapture() {
    assertEquals(8,new WindowsInputProbe.LastInput().cbSize);
    var sample=WindowsInputProbe.read();
    assertNotNull(sample);assertTrue(sample.lastInput()>=0 && sample.lastInput()<=0xffffffffL);
    assertTrue(sample.uptimeMillis()>0);
  }
  @Test @EnabledOnOs(OS.WINDOWS) void reproducibleReadOnlyProbeBenchmark() {
    WindowsInputProbe.read(); // Exclude lazy library initialization.
    long[] elapsed=new long[1000];
    for(int i=0;i<elapsed.length;i++){
      long start=System.nanoTime();assertNotNull(WindowsInputProbe.read());elapsed[i]=System.nanoTime()-start;
    }
    java.util.Arrays.sort(elapsed);
    System.out.printf("Activity native probe benchmark: samples=%d median_us=%.2f p95_us=%.2f PowerShell=0 screenshots=0%n",
        elapsed.length,elapsed[500]/1000.0,elapsed[950]/1000.0);
  }
}
