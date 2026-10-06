package dev.mikoto2000.rei.vectordocument;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rei.rerank")
public record RerankProperties(@DefaultValue("true") boolean enabled,
    String baseUrl, String apiKey, String model, String path,@DefaultValue("false") boolean inheritRunModelBudget) {
  public RerankProperties(boolean enabled,String baseUrl,String apiKey,String model,String path) {
    this(enabled,baseUrl,apiKey,model,path,false);
  }
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public RerankProperties {
    if (path == null || path.isBlank()) path = "/v1/rerank";
  }
}
