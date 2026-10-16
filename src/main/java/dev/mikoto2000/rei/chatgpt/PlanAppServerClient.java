package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.CANCELLED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.PERMISSION_DENIED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.PROTOCOL;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.SEND_LIMIT;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TIMEOUT;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.event.CredentialRedactor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Own OAuth lease + a fresh native process; no alternate credentials or billing fallback. */
public final class PlanAppServerClient implements PlanInference {
  public record Started(PlanAppServerRpc.Session session, String cwd) {}

  public interface Launcher {
    Started start(TokenSet ownToken, Duration timeout, BooleanSupplier stop);

    default String authority() {
      return "codex-0.154.0-read-snapshots";
    }

    default String version() {
      return "0.154.0";
    }

    default String platform() {
      return "linux";
    }
  }

  private final PlanClient accounts;
  private final Launcher launcher;
  private final ToolPermissionGuard permissions;
  private final ThreadLocal<PlanAppServerRpc.Trace> traces = new ThreadLocal<>();

  private record Budget(
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation, int maxCalls) {}

  private final ThreadLocal<Budget> nativeBudget = new ThreadLocal<>();
  private final ThreadLocal<Integer> nativeTokens = new ThreadLocal<>();

  public void nativeBudget(
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation budget, int maxCalls) {
    nativeBudget.set(new Budget(budget, maxCalls));
  }

  public int takeNativeTokens() {
    var value = nativeTokens.get();
    nativeTokens.remove();
    return value == null ? 0 : value;
  }

  private final ObjectMapper json =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  public PlanAppServerClient(
      PlanClient accounts, Launcher launcher, ToolPermissionGuard permissions) {
    this.accounts = accounts;
    this.launcher = launcher;
    this.permissions = permissions;
  }

  public PlanAppServerRpc.Trace takeTrace() {
    var trace = traces.get();
    traces.remove();
    return trace;
  }

  public String authority() {
    return launcher.authority();
  }

  private static String snapshotInstructions(String instructions) {
    return instructions
        .replace(
            "The external backend has zero tools.",
            "The external backend permits only rei_read_source for the exact approved snapshots. Initial sources contain metadata; fetch their content with that tool. All native tools are disabled.")
        .replace(
            "Never invoke tools, change files or delegate.",
            "Only rei_read_source may read each supplied snapshot once. Never execute commands, change files, use network tools or delegate.");
  }

  public static int encodedSnapshotBytes(String instructions, List<PlanClient.Input> input) {
    try {
      return new ObjectMapper()
          .writeValueAsBytes(
              Map.of("instructions", snapshotInstructions(instructions), "input", input))
          .length;
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw ChatGptException.of(PROTOCOL);
    }
  }

  public PlanSseAssembler.Result inferHistory(
      String account,
      String model,
      String instructions,
      List<PlanClient.Input> input,
      Duration timeout,
      int sendLimit,
      int receiveLimit,
      BooleanSupplier cancelled,
      Runnable onDispatch) {
    traces.remove();
    nativeTokens.set(0);
    var budget = nativeBudget.get();
    nativeBudget.remove();
    var owner = AgentRunScope.current();
    if (owner == null || owner.projectId() == null || input.isEmpty() || input.size() > 16)
      throw ChatGptException.of(PERMISSION_DENIED);
    long deadline = System.nanoTime() + timeout.toNanos();
    Map<String, String> snapshots = new LinkedHashMap<>();
    Set<String> served = new HashSet<>();
    List<PlanClient.Input> nativeInput;
    String adjusted = snapshotInstructions(instructions);
    try {
      if (encodedSnapshotBytes(instructions, input) > sendLimit)
        throw ChatGptException.of(SEND_LIMIT);
      var task = json.readTree(input.getFirst().content());
      if (!task.path("sources").isArray()) throw ChatGptException.of(PERMISSION_DENIED);
      for (var source : task.path("sources")) {
        String path = source.path("path").asText();
        if (!source.path("content").isTextual()
            || path.isBlank()
            || snapshots.put(path, source.path("content").asText()) != null)
          throw ChatGptException.of(PERMISSION_DENIED);
        ((com.fasterxml.jackson.databind.node.ObjectNode) source).remove("content");
      }
      nativeInput = new ArrayList<>(input);
      nativeInput.set(0, new PlanClient.Input("user", json.writeValueAsString(task)));
    } catch (ChatGptException e) {
      throw e;
    } catch (Exception e) {
      throw ChatGptException.of(PROTOCOL);
    }
    if (cancelled.getAsBoolean()) throw ChatGptException.of(CANCELLED);
    var lease = accounts.lease(account, model);
    BooleanSupplier stop = () -> cancelled.getAsBoolean() || !lease.valid().getAsBoolean();
    if (stop.getAsBoolean()) throw ChatGptException.of(CANCELLED);
    var remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    if (System.nanoTime() >= deadline) throw ChatGptException.of(TIMEOUT);
    var started = launcher.start(lease.tokens(), remaining, stop);
    var result =
        new PlanAppServerRpc(launcher.version(), launcher.platform())
            .execute(
                started.session(),
                started.cwd(),
                model,
                adjusted,
                nativeInput,
                Duration.ofNanos(Math.max(1, deadline - System.nanoTime())),
                receiveLimit,
                stop,
                onDispatch,
                path -> {
                  if (stop.getAsBoolean() || !snapshots.containsKey(path) || served.contains(path))
                    throw ChatGptException.of(PERMISSION_DENIED);
                  permissions.check("readFile", path, owner);
                  // Reserve the next inference before replying to a tool; parallel tool replies
                  // over-reserve conservatively.
                  if (budget != null
                      && budget.reservation() != null
                      && (budget.reservation().tokenExhausted()
                          || budget.reservation().usageUnknown()
                          || !budget.reservation().tryReserve()))
                    throw ChatGptException.of(PERMISSION_DENIED);
                  served.add(path);
                  return snapshots.get(path);
                },
                budget == null ? 16 : Math.min(16, Math.max(0, budget.maxCalls() - 1)),
                tokens -> {
                  if (budget != null && budget.reservation() != null) {
                    int previous = nativeTokens.get();
                    int delta = tokens - previous;
                    if (delta < 0) throw ChatGptException.of(PROTOCOL);
                    nativeTokens.set(tokens);
                    if (delta > 0) budget.reservation().recordTotalTokens(delta);
                    if (budget.reservation().usageUnknown())
                      throw ChatGptException.of(PERMISSION_DENIED);
                  }
                });
    var trace = result.trace();
    for (String value :
        List.of(Objects.toString(trace.threadId(), ""), Objects.toString(trace.turnId(), "")))
      if (value.contains(lease.tokens().accessToken())
          || !CredentialRedactor.redact(value).equals(value)) throw ChatGptException.of(PROTOCOL);
    traces.set(trace);
    if (result.result().text().contains(lease.tokens().accessToken())
        || dev.mikoto2000.rei.subagent.ExternalDelegationContentGuard.hasUnmaskedCredentials(
            result.result().text()))
      return new PlanSseAssembler.Result(
          PlanSseAssembler.State.FAILED,
          "",
          result.result().totalTokens(),
          result.result().responseId(),
          PERMISSION_DENIED);
    return result.result();
  }
}
