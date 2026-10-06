package dev.mikoto2000.rei.llm;

import java.util.List;
import org.springframework.ai.embedding.*;
import org.springframework.ai.document.Document;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Provider usage is charged before vectors can be indexed, cached or used for retrieval. */
public final class BudgetedEmbeddingModel implements EmbeddingModel {
  private final EmbeddingModel delegate;
  private volatile int dimensions;
  public BudgetedEmbeddingModel(EmbeddingModel delegate){this.delegate=delegate;}
  public EmbeddingResponse call(EmbeddingRequest request) {
    var budget=ModelCallBudgetScope.current();
    if(budget==null)return delegate.call(request);
    budget.run();boolean reported=false;
    try {
      var response=delegate.call(request);
      var usage=!budget.tokenLimitEnabled()||response==null||response.getMetadata()==null?null:response.getMetadata().getUsage();
      Integer tokens=usage==null?null:usage.getTotalTokens();
      reported=true;budget.recordTotalTokens(tokens);
      if(response!=null&&response.getResult()!=null&&response.getResult().getOutput()!=null)
        dimensions=response.getResult().getOutput().length;
      return response;
    } catch(RuntimeException error) {
      RunCancellation.propagate(error);
      if(!reported)budget.recordTotalTokens(null);
      throw error;
    }
  }
  public float[] embed(String text) {
    return ModelCallBudgetScope.current()==null?delegate.embed(text):EmbeddingModel.super.embed(text);
  }
  public List<float[]> embed(List<String> texts) {
    return ModelCallBudgetScope.current()==null?delegate.embed(texts):EmbeddingModel.super.embed(texts);
  }
  public EmbeddingResponse embedForResponse(List<String> texts) {
    return ModelCallBudgetScope.current()==null?delegate.embedForResponse(texts):EmbeddingModel.super.embedForResponse(texts);
  }
  public List<float[]> embed(List<Document> documents,EmbeddingOptions options,BatchingStrategy strategy) {
    return ModelCallBudgetScope.current()==null?delegate.embed(documents,options,strategy):EmbeddingModel.super.embed(documents,options,strategy);
  }
  public float[] embed(Document document) {
    if(ModelCallBudgetScope.current()==null)return delegate.embed(document);
    return embed(delegate.getEmbeddingContent(document));
  }
  public String getEmbeddingContent(Document document){return delegate.getEmbeddingContent(document);}
  public synchronized int dimensions() {
    if(ModelCallBudgetScope.current()==null)return delegate.dimensions();
    if(dimensions<1)dimensions=embed("Hello World").length;
    return dimensions;
  }
}
