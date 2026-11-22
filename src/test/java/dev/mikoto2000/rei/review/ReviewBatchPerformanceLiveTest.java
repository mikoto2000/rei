package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.storage.StorageDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in isolated real-LLM comparison; never calls the production Rei project or modifies it. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "REI_REVIEW_BATCH_LIVE", matches = "true")
class ReviewBatchPerformanceLiveTest {
  @TempDir Path temporary;

  @Test
  void compareThreeProtocolsOnTheSameSmallFiles() throws Exception {
    String url = Objects.requireNonNull(System.getenv("REI_REVIEW_EVAL_URL"));
    String model = Objects.requireNonNull(System.getenv("REI_REVIEW_EVAL_MODEL"));
    String key = Objects.requireNonNull(System.getenv("REI_REVIEW_EVAL_API_KEY"));
    boolean thinking =
        Boolean.parseBoolean(System.getenv().getOrDefault("REI_REVIEW_EVAL_THINKING", "false"));
    int trials = Integer.parseInt(System.getenv().getOrDefault("REI_REVIEW_BATCH_TRIALS", "3"));
    if (trials < 1 || trials > 5) throw new IllegalArgumentException("Trials must be 1..5");
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    var measurements = new ArrayList<ReviewBatchPerformanceTest.Measurement>();
    Path output = Path.of("target/review-batch-live.json");
    try {
      for (int trial = 0; trial < trials; trial++) {
        var modes = ReviewBatchPerformanceTest.Mode.values();
        for (int n = 0; n < modes.length; n++) {
          var mode = modes[(n + trial) % modes.length]; // Rotate order to reduce warm-cache bias.
          var result =
              ReviewBatchPerformanceTest.evaluate(
                  temporary.resolve("trial-" + trial + "-" + mode),
                  mode,
                  (instruction, input) -> {
                    String body =
                        StorageDatabase.JSON.writeValueAsString(
                            Map.of(
                                "model",
                                model,
                                "messages",
                                List.of(
                                    Map.of("role", "system", "content", instruction),
                                    Map.of("role", "user", "content", input)),
                                "temperature",
                                0,
                                "max_tokens",
                                4096,
                                "chat_template_kwargs",
                                Map.of("enable_thinking", thinking)));
                    var request =
                        HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(180))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + key)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200)
                      throw new IllegalStateException("Evaluation HTTP " + response.statusCode());
                    var json = StorageDatabase.JSON.readTree(response.body());
                    var usage = json.path("usage");
                    var cached = usage.path("prompt_tokens_details").path("cached_tokens");
                    return new ReviewBatchPerformanceTest.Reply(
                        json.path("choices").path(0).path("message").path("content").asText(),
                        usage.has("prompt_tokens") ? usage.path("prompt_tokens").asLong() : null,
                        usage.has("completion_tokens")
                            ? usage.path("completion_tokens").asLong()
                            : null,
                        cached.isNumber() ? cached.asLong() : null);
                  });
          measurements.add(result);
          System.out.println("review_batch_live trial=" + trial + " " + result);
        }
      }
    } finally {
      Files.createDirectories(output.getParent());
      Files.writeString(
          output,
          StorageDatabase.JSON
              .writerWithDefaultPrettyPrinter()
              .writeValueAsString(
                  Map.of(
                      "model",
                      model,
                      "thinking",
                      thinking,
                      "trials",
                      trials,
                      "fixtureFiles",
                      ReviewBatchPerformanceTest.FIXTURES.size(),
                      "measurementScope",
                      "Isolated model evaluation with real frozen acquisition and durable assessment; excludes general-agent discovery/tool selection. Shared server load is uncontrolled; token usage includes cache hits.",
                      "measurements",
                      measurements)));
    }
    assertThat(measurements).hasSize(trials * 3);
    assertThat(
            measurements.stream()
                .filter(m -> m.mode() != ReviewBatchPerformanceTest.Mode.BASELINE)
                .toList())
        .allSatisfy(m -> {
          assertThat(m.complete()).isTrue();
          assertThat(m.reviewedFiles()).isEqualTo(4);
          assertThat(m.recall()).isEqualTo(1);
          assertThat(m.precision()).isEqualTo(1);
        });
  }
}
