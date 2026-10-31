package dev.mikoto2000.rei.tooldelivery;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** 同期ツール実行中だけ存在する、実際の依頼とサーバー側の全文取得予算。 */
public final class FileReadContext implements AutoCloseable {
  /** JSONの自己申告では作らない、実際に使用した全文取得上限。 */
  public record Grant(int tokens)
      implements dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.FileReadBudgetValue {}

  public record Decision(String mode, int tokens) {}

  private static final ThreadLocal<FileReadContext> CURRENT = new ThreadLocal<>();
  private final FileReadContext previous;
  private final Prompt prompt;
  private final int calls;
  private final String assistant;
  private Grant grant;
  private Decision decision;

  private FileReadContext(Prompt prompt, int calls, String assistant) {
    previous = CURRENT.get();
    this.prompt =
        prompt.getOptions()
                    instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
                && options.getToolContext() != null
                && options.getToolContext().get(Projection.class.getName())
                    instanceof Projection projection
            ? projection.prompt()
            : prompt;
    this.calls = Math.max(1, calls);
    this.assistant = assistant;
    CURRENT.set(this);
  }

  private record Projection(Prompt prompt) {}

  /** 会話履歴は維持し、容量計算だけ実際にモデルへ送った投影を参照する。 */
  public static Prompt withProjection(Prompt raw, Prompt projected) {
    if (!(raw.getOptions()
        instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options)) return raw;
    var context = new java.util.HashMap<String, Object>();
    if (options.getToolContext() != null) context.putAll(options.getToolContext());
    context.put(Projection.class.getName(), new Projection(projected));
    return new Prompt(raw.getInstructions(), options.mutate().toolContext(context).build());
  }

  public static FileReadContext open(Prompt prompt, ChatResponse response) {
    int calls =
        response.getResults().stream().mapToInt(g -> g.getOutput().getToolCalls().size()).sum();
    String assistant =
        response.getResults().stream()
            .map(g -> g.getOutput().toString())
            .collect(java.util.stream.Collectors.joining());
    return new FileReadContext(prompt, calls, assistant);
  }

  public static FileReadContext open(Prompt prompt, int calls, String assistant) {
    return new FileReadContext(prompt, calls, assistant);
  }

  public static Grant grant() {
    return CURRENT.get() == null ? null : CURRENT.get().grant;
  }

  public static boolean active() {
    return CURRENT.get() != null;
  }

  public static Decision decision() {
    return CURRENT.get() == null ? null : CURRENT.get().decision;
  }

  public static void selected(String mode, int tokens) {
    if (CURRENT.get() != null) CURRENT.get().decision = new Decision(mode, tokens);
  }

  public static int bulkBudget(
      int maximum,
      java.util.function.ToIntFunction<Prompt> inputLimit,
      java.util.function.ToLongFunction<Prompt> promptCost,
      java.util.function.ToIntFunction<String> textCost) {
    var current = CURRENT.get();
    if (current == null) return 0;
    long available =
        inputLimit.applyAsInt(current.prompt)
            - promptCost.applyAsLong(current.prompt)
            - textCost.applyAsInt(current.assistant)
            - 512L * current.calls;
    return (int) Math.max(0, Math.min(maximum, available / current.calls));
  }

  public static void used(int tokens) {
    if (CURRENT.get() != null) CURRENT.get().grant = new Grant(tokens);
  }

  @Override
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
