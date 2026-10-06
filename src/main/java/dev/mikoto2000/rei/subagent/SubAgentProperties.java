package dev.mikoto2000.rei.subagent;

import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;

@ConfigurationProperties(prefix = "rei.subagents")
public class SubAgentProperties {
  private Path directory = ReiDataDirectory.current().resolve("config/subagents");
  private Set<String> models = Set.of();
  private int standaloneMaxLlmCalls;
  private long standaloneMaxTotalTokens;
  public int getStandaloneMaxLlmCalls(){return standaloneMaxLlmCalls;}
  public void setStandaloneMaxLlmCalls(int value) {
    if(value<0||value>1000)throw new IllegalArgumentException("Standalone SubAgent call limit must be 0 to 1000");
    standaloneMaxLlmCalls=value;
  }
  public long getStandaloneMaxTotalTokens(){return standaloneMaxTotalTokens;}
  public void setStandaloneMaxTotalTokens(long value) {
    if(value<0)throw new IllegalArgumentException("Standalone SubAgent token limit must be nonnegative");
    standaloneMaxTotalTokens=value;
  }
  public Path getDirectory() { return directory; }
  public void setDirectory(Path directory) { this.directory = directory; }
  public Set<String> getModels() { return models; }
  public void setModels(Set<String> models) { this.models = Set.copyOf(models); }
}
