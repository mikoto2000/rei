package dev.mikoto2000.rei.timing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Reproducible synthetic diagnostic overhead, never a provider cache benchmark. */
@org.junit.jupiter.api.Tag("integration")
class PrefixDiagnosticScenariosTest {
  @Test
  void recordsBoundedMetadataForEightRepresentativeStructures() {
    for (String scenario :
        List.of(
            "chat",
            "session",
            "tool-loop",
            "file-read",
            "file-write",
            "planning",
            "compaction",
            "skill-selection")) {
      var diagnostic = new PrefixDiagnostics();
      var sources =
          List.of(
              new PrefixDiagnostics.Source("SYSTEM", "fixed instruction ".repeat(512)),
              new PrefixDiagnostics.Source("TOOL_DEFINITIONS", "stable schema ".repeat(256)),
              new PrefixDiagnostics.Source(
                  "HISTORY",
                  scenario
                      + " current content ".repeat(scenario.equals("compaction") ? 4096 : 256)));
      for (int warm = 0; warm < 20; warm++) diagnostic.observe(sources);
      var samples = new long[101];
      PrefixDiagnostics.Snapshot result = null;
      for (int repeat = 0; repeat < samples.length; repeat++) {
        long start = System.nanoTime();
        result = diagnostic.observe(sources);
        samples[repeat] = System.nanoTime() - start;
      }
      Arrays.sort(samples);
      assertNull(result.firstChanged());
      assertFalse(result.toString().contains("fixed instruction"));
      assertEquals(3, result.regions().size());
      System.out.printf(
          Locale.ROOT,
          "PREFIX_DIAGNOSTIC,%s,median_us=%.3f,min_us=%.3f,max_us=%.3f,regions=%d%n",
          scenario,
          samples[50] / 1000d,
          samples[0] / 1000d,
          samples[100] / 1000d,
          result.regions().size());
    }
  }
}
