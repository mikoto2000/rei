package dev.mikoto2000.rei.storage;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 検証条件を再現するための判定期間DiskSampler。
 *
 * <p>OS physical-disk idle-time counter, independent of application COMMIT counts.
 */
final class WindowsDiskSampler implements AutoCloseable {
  /** 検証条件を再現するためのPdh。 */
  interface Pdh extends Library {
    /** {@code PdhOpenQueryW} の条件を再現するための補助情報を返す。 */
    int PdhOpenQueryW(WString source, Pointer user, PointerByReference query);

    /** {@code PdhAddEnglishCounterW} の条件を再現するための補助情報を返す。 */
    int PdhAddEnglishCounterW(
        Pointer query, WString path, Pointer user, PointerByReference counter);

    /** {@code PdhCollectQueryData} の条件を再現するための補助情報を返す。 */
    int PdhCollectQueryData(Pointer query);

    /** {@code PdhGetFormattedCounterValue} の条件を再現するための補助情報を返す。 */
    int PdhGetFormattedCounterValue(Pointer counter, int format, IntByReference type, Value value);

    /** {@code PdhCloseQuery} の条件を再現するための補助情報を返す。 */
    int PdhCloseQuery(Pointer query);
  }

  /** 検証条件を再現するための数値。 */
  public static class Number extends Union {
    /** 検証に使う数値。 */
    public double number;

    /** 検証に使う整数。 */
    public long integer;

    /** 検証に使うテキスト。 */
    public Pointer text;
  }

  /** 検証条件を再現するための値。 */
  @Structure.FieldOrder({"status", "value"})
  public static class Value extends Structure {
    /** 検証に使う状態。 */
    public int status;

    /** 検証に使う値。 */
    public Number value = new Number();
  }

  /** 検証に使う活動中。 */
  final List<Double> active = new ArrayList<>();

  /** 検証に使うheap。 */
  final List<Long> heap = new ArrayList<>();

  /** 検証に使う起動単位の識別子。 */
  final String instance =
      System.getProperty("rei.io.disk", System.getenv().getOrDefault("REI_IO_DISK", "_Total"));

  /** 検証に使う実行先。 */
  final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

  /** 検証に使うpdh。 */
  Pdh pdh;

  /** 検証に使う検索条件、counter。 */
  Pointer query, counter;

  /** 検証に使う利用できない。 */
  String unavailable;

  /** 検証に使う終了済み。 */
  boolean closed;

  /** 検証条件に必要な値を受け取り、WindowsDiskSamplerを用意する。 */
  WindowsDiskSampler() {
    try {
      if (!System.getProperty("os.name").startsWith("Windows"))
        throw new IllegalStateException("Windows only");
      pdh = Native.load("pdh", Pdh.class);
      var q = new PointerByReference();
      check(pdh.PdhOpenQueryW(null, null, q));
      query = q.getValue();
      var c = new PointerByReference();
      check(
          pdh.PdhAddEnglishCounterW(
              query, new WString("\\PhysicalDisk(" + instance + ")\\% Idle Time"), null, c));
      counter = c.getValue();
      check(pdh.PdhCollectQueryData(query));
      executor.scheduleAtFixedRate(this::sample, 100, 100, TimeUnit.MILLISECONDS);
    } catch (RuntimeException | LinkageError error) {
      unavailable = error.toString();
    }
  }

  /** 検証用に、後続の処理を進められるか、現在の条件を確認する。 */
  private static void check(int status) {
    if (status != 0) throw new IllegalStateException("PDH status 0x" + Integer.toHexString(status));
  }

  /** {@code sample} の条件を再現するため、検証用の操作や状態を扱う。 */
  synchronized void sample() {
    if (closed) return;
    try {
      check(pdh.PdhCollectQueryData(query));
      var value = new Value();
      value.value.setType(double.class);
      check(pdh.PdhGetFormattedCounterValue(counter, 0x200, null, value));
      if (value.status != 0 && value.status != 1)
        throw new IllegalStateException("PDH sample status " + value.status);
      active.add(Math.max(0, Math.min(100, 100 - value.value.number)));
      var runtime = Runtime.getRuntime();
      heap.add(runtime.totalMemory() - runtime.freeMemory());
    } catch (RuntimeException error) {
      unavailable = error.toString();
    }
  }

  /** {@code summary} の条件を再現するための補助情報を返す。 */
  synchronized Map<String, Object> summary() {
    var result = new LinkedHashMap<String, Object>();
    result.put("diskInstance", instance);
    result.put("diskSamples", active.size());
    if (!active.isEmpty()) {
      var values = active.stream().mapToDouble(Double::doubleValue).sorted().toArray();
      result.put("diskActiveMeanPercent", Arrays.stream(values).average().orElseThrow());
      result.put("diskActiveP95Percent", values[(int) Math.ceil(values.length * .95) - 1]);
      result.put("heapMaxBytes", heap.stream().mapToLong(Long::longValue).max().orElseThrow());
    } else
      result.put(
          "diskActiveUnavailable",
          unavailable == null ? "trial shorter than sampling interval" : unavailable);
    return result;
  }

  /** 検証で利用した対象の終了を扱う。 */
  public void close() {
    executor.shutdownNow();
    synchronized (this) {
      closed = true;
      if (query != null) pdh.PdhCloseQuery(query);
      query = null;
    }
  }
}
