package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 用途に応じたモデル利用に必要なBudgeted埋め込み表現モデルを扱う。
 *
 * <p>Provider usage is charged before vectors can be indexed, cached or used for retrieval.
 */
public final class BudgetedEmbeddingModel implements EmbeddingModel {
  /** 委譲先。 */
  private final EmbeddingModel delegate;

  /** 次元数。 */
  private volatile int dimensions;

  /** 指定された値や利用先を使って、BudgetedEmbeddingModelを初期化する。 */
  public BudgetedEmbeddingModel(EmbeddingModel delegate) {
    this.delegate = delegate;
  }

  /** 用途に応じたモデル利用の要求を実行し、呼び出し元へ結果を返す。 */
  public EmbeddingResponse call(EmbeddingRequest request) {
    var budget = ModelCallBudgetScope.current();
    if (budget == null) return delegate.call(request);
    budget.run();
    boolean reported = false;
    try {
      var response = delegate.call(request);
      var usage =
          !budget.tokenLimitEnabled() || response == null || response.getMetadata() == null
              ? null
              : response.getMetadata().getUsage();
      Integer tokens = usage == null ? null : usage.getTotalTokens();
      reported = true;
      budget.recordTotalTokens(tokens);
      if (response != null
          && response.getResult() != null
          && response.getResult().getOutput() != null)
        dimensions = response.getResult().getOutput().length;
      return response;
    } catch (RuntimeException error) {
      RunCancellation.propagate(error);
      if (!reported) budget.recordTotalTokens(null);
      throw error;
    }
  }

  /** 用途に応じたモデル利用で用いる埋め込み表現の生成を返す。 */
  public float[] embed(String text) {
    return ModelCallBudgetScope.current() == null
        ? delegate.embed(text)
        : EmbeddingModel.super.embed(text);
  }

  /** 用途に応じたモデル利用で用いる埋め込み表現の生成を返す。 */
  public List<float[]> embed(List<String> texts) {
    return ModelCallBudgetScope.current() == null
        ? delegate.embed(texts)
        : EmbeddingModel.super.embed(texts);
  }

  /** 用途に応じたモデル利用で用いる埋め込み表現の生成対象応答を返す。 */
  public EmbeddingResponse embedForResponse(List<String> texts) {
    return ModelCallBudgetScope.current() == null
        ? delegate.embedForResponse(texts)
        : EmbeddingModel.super.embedForResponse(texts);
  }

  /** 用途に応じたモデル利用で用いる埋め込み表現の生成を返す。 */
  public List<float[]> embed(
      List<Document> documents, EmbeddingOptions options, BatchingStrategy strategy) {
    return ModelCallBudgetScope.current() == null
        ? delegate.embed(documents, options, strategy)
        : EmbeddingModel.super.embed(documents, options, strategy);
  }

  /** 用途に応じたモデル利用で用いる埋め込み表現の生成を返す。 */
  public float[] embed(Document document) {
    if (ModelCallBudgetScope.current() == null) return delegate.embed(document);
    return embed(delegate.getEmbeddingContent(document));
  }

  /** 用途に応じたモデル利用で参照する埋め込み表現内容を返す。 */
  public String getEmbeddingContent(Document document) {
    return delegate.getEmbeddingContent(document);
  }

  /** 用途に応じたモデル利用で用いる次元数を返す。 */
  public synchronized int dimensions() {
    if (ModelCallBudgetScope.current() == null) return delegate.dimensions();
    if (dimensions < 1) dimensions = embed("Hello World").length;
    return dimensions;
  }
}
