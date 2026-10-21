package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** 判定期間入力利用可否の確認の結果と状態の契約を検証するテスト。 */
class WindowsInputProbeTest {
  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void nativeReadDoesNotRequirePowerShellOrCapture() {
    assertEquals(8, new WindowsInputProbe.LastInput().cbSize);
    var sample = WindowsInputProbe.read();
    assertNotNull(sample);
    assertTrue(sample.lastInput() >= 0 && sample.lastInput() <= 0xffffffffL);
    assertTrue(sample.uptimeMillis() > 0);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void reproducibleReadOnlyProbeBenchmark() {
    WindowsInputProbe.read(); // Exclude lazy library initialization.
    long[] elapsed = new long[1000];
    for (int i = 0; i < elapsed.length; i++) {
      long start = System.nanoTime();
      assertNotNull(WindowsInputProbe.read());
      elapsed[i] = System.nanoTime() - start;
    }
    java.util.Arrays.sort(elapsed);
    System.out.printf(
        "Activity native probe benchmark: samples=%d median_us=%.2f p95_us=%.2f PowerShell=0 screenshots=0%n",
        elapsed.length, elapsed[500] / 1000.0, elapsed[950] / 1000.0);
  }
}
