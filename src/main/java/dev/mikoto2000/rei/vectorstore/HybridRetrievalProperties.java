package dev.mikoto2000.rei.vectorstore;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rei.vector-document.retrieval")
public record HybridRetrievalProperties(boolean enabled,int candidateLimit,int rankConstant, boolean bm25Enabled) {
  public HybridRetrievalProperties(boolean enabled, int candidateLimit, int rankConstant) {
    this(enabled, candidateLimit, rankConstant, false);
  }
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public HybridRetrievalProperties {
    if(candidateLimit==0)candidateLimit=40;
    if(rankConstant==0)rankConstant=60;
    if(candidateLimit<1 || candidateLimit>256 || rankConstant<1 || rankConstant>1000)throw new IllegalArgumentException("Invalid hybrid retrieval limits");
  }
}
