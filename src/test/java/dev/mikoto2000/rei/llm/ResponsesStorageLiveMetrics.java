package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.util.Map;

/** Local timing of an actual received payload; never displays or transmits its contents. */
final class ResponsesStorageLiveMetrics {
  private ResponsesStorageLiveMetrics() {}

  static void run(ResponsesAcceptanceRuntime runtime) throws Exception {
    ModelMessagePayloadStore.Scope scope;
    ModelMessagePayloadStore.Ref ref;
    String run;
    long bytes;
    // Close the metadata cursor before invoking the store, which owns its own transactions.
    try (var connection = runtime.source("payload.db").getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT id,sha256,project,root_path,conversation,run,size FROM model_message_payloads ORDER BY size DESC LIMIT 1");
        var rows = statement.executeQuery()) {
      if (!rows.next()) throw new IllegalStateException("No real received payload available");
      scope =
          new ModelMessagePayloadStore.Scope(
              rows.getString(3), rows.getString(4), rows.getString(5));
      ref = new ModelMessagePayloadStore.Ref(rows.getString(1), rows.getString(2));
      run = rows.getString(6);
      bytes = rows.getLong(7);
    }
    var codec = new dev.mikoto2000.rei.modelmessage.ModelMessageCodec();
    String sample = "metrics-" + java.util.UUID.randomUUID();
    var loadTimes = new java.util.ArrayList<Double>();
    var saveTimes = new java.util.ArrayList<Double>();
    for (int i = 0; i < 5; i++) {
      long started = System.nanoTime();
      var message = runtime.payloads.load(scope, ref);
      loadTimes.add((System.nanoTime() - started) / 1000000.0);
      started = System.nanoTime();
      var saved = runtime.payloads.save(scope, run, sample + "-" + i, message);
      saveTimes.add((System.nanoTime() - started) / 1000000.0);
      if (!ref.sha256().equals(saved.sha256())
          || !java.util.Arrays.equals(
              codec.encode(message), codec.encode(runtime.payloads.load(scope, saved))))
        throw new IllegalStateException("Real payload round trip mismatch");
    }
    runtime.report(
        "STORAGE_METRICS",
        Map.of(
            "success",
            true,
            "samples",
            5,
            "sample_bytes",
            bytes,
            "sample_hash",
            ref.sha256(),
            "load_ms",
            loadTimes,
            "save_ms",
            saveTimes,
            "storage_usage",
            runtime.payloads.usage(scope, run),
            "budget",
            runtime.budget.snapshot()));
  }
}
