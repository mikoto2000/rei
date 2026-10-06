package dev.mikoto2000.rei.skills;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounds provider calls and outstanding work even when a provider ignores interruption. */
public final class SkillEmbeddingClient implements SkillMetadataEmbedding, AutoCloseable {
  private final Supplier<SkillMetadataEmbedding> provider;
  private final Duration timeout;
  private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(1), runnable -> {
        var thread = new Thread(runnable, "skill-embedding"); thread.setDaemon(true); return thread;
      });
  public SkillEmbeddingClient(Supplier<SkillMetadataEmbedding> provider, Duration timeout) {
    this.provider = provider; this.timeout = timeout;
  }
  @Override public List<float[]> embed(List<String> texts) {
    var budget=dev.mikoto2000.rei.llm.ModelCallBudgetScope.current();
    var future = executor.submit(() -> {
      try(var scope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(budget)) {
      var model = provider.get();
      if (model == null) throw new IllegalStateException("Embedding provider unavailable");
      return model.embed(texts);
      }
    });
    try { return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
    catch (InterruptedException error) {
      future.cancel(true); Thread.currentThread().interrupt();
      throw new CancellationException("Skill embedding interrupted");
    } catch (TimeoutException error) {
      future.cancel(true); throw new IllegalStateException("Skill embedding timed out");
    } catch (ExecutionException error) {
      if (error.getCause() instanceof RuntimeException cause) throw cause;
      throw new IllegalStateException("Skill embedding failed", error.getCause());
    } finally { executor.purge(); }
  }
  @Override public void close() {
    for (var pending : executor.shutdownNow()) if (pending instanceof Future<?> future) future.cancel(true);
  }
}
