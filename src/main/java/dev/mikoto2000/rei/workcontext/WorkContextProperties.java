package dev.mikoto2000.rei.workcontext;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("rei.work-context")
public record WorkContextProperties(@DefaultValue("false") boolean autoUpdate,
    @DefaultValue("true") boolean autoPresent,@DefaultValue("1200") int maxContextTokens,
    @DefaultValue("12000") int maxInputTokens,@DefaultValue("120") int timeoutSeconds,@DefaultValue("20") int maxTurns) {
  public WorkContextProperties {
    if(maxContextTokens<128||maxInputTokens<1024||timeoutSeconds<1||maxTurns<1||maxTurns>100)
      throw new IllegalArgumentException("Invalid Work Context budgets");
  }
}
