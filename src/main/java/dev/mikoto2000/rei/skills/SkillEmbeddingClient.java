package dev.mikoto2000.rei.skills;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * スキル埋め込み表現を外部の接続先へ依頼する窓口。
 *
 * <p>Bounds provider calls and outstanding work even when a provider ignores interruption.
 */
public final class SkillEmbeddingClient implements SkillMetadataEmbedding, AutoCloseable {
  /** 提供元。 */
  private final Supplier<SkillMetadataEmbedding> provider;

  /** タイムアウト。 */
  private final Duration timeout;

  /** 実行先。 */
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          runnable -> {
            var thread = new Thread(runnable, "skill-embedding");
            thread.setDaemon(true);
            return thread;
          });

  /** 指定された値や利用先を使って、SkillEmbeddingClientを初期化する。 */
  public SkillEmbeddingClient(Supplier<SkillMetadataEmbedding> provider, Duration timeout) {
    this.provider = provider;
    this.timeout = timeout;
  }

  /** スキルの利用で用いる埋め込み表現の生成を返す。 */
  @Override
  public List<float[]> embed(List<String> texts) {
    var budget = dev.mikoto2000.rei.llm.ModelCallBudgetScope.current();
    var future =
        executor.submit(
            () -> {
              try (var scope = dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(budget)) {
                var model = provider.get();
                if (model == null)
                  throw new IllegalStateException("Embedding provider unavailable");
                return model.embed(texts);
              }
            });
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      future.cancel(true);
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new CancellationException("Skill embedding interrupted");
    } catch (TimeoutException error) {
      future.cancel(true);
      throw new IllegalStateException("Skill embedding timed out");
    } catch (ExecutionException error) {
      if (error.getCause() instanceof RuntimeException cause) throw cause;
      throw new IllegalStateException("Skill embedding failed", error.getCause());
    } finally {
      executor.purge();
    }
  }

  /** スキルの利用で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    for (var pending : executor.shutdownNow())
      if (pending instanceof Future<?> future) future.cancel(true);
  }
}
