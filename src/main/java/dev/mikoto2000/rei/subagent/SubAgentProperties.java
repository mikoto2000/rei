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
  private int maxTransientModelRetries;
  private int maxTransientReadToolRetries;
  private boolean durableEnabled;
  private boolean dagEnabled;
  private java.time.Duration dagTimeout=java.time.Duration.ofSeconds(120);
  public boolean isDagEnabled(){return dagEnabled;}
  public void setDagEnabled(boolean value){dagEnabled=value;}
  public java.time.Duration getDagTimeout(){return dagTimeout;}
  public void setDagTimeout(java.time.Duration value){if(value==null || value.compareTo(java.time.Duration.ofSeconds(1))<0 || value.compareTo(java.time.Duration.ofSeconds(120))>0)throw new IllegalArgumentException("DAG timeout must be 1 to 120 seconds");dagTimeout=value;}
  private long durableMaxTotalTokens;
  public boolean isDurableEnabled(){return durableEnabled;}
  public void setDurableEnabled(boolean value){durableEnabled=value;}
  public long getDurableMaxTotalTokens(){return durableMaxTotalTokens;}
  public void setDurableMaxTotalTokens(long value){if(value<0)throw new IllegalArgumentException("Durable child token limit must be nonnegative");durableMaxTotalTokens=value;}
  public int getMaxTransientReadToolRetries(){return maxTransientReadToolRetries;}
  public void setMaxTransientReadToolRetries(int value){if(value<0||value>3)throw new IllegalArgumentException("Transient read Tool retries must be 0 to 3");maxTransientReadToolRetries=value;}
  public int getMaxTransientModelRetries(){return maxTransientModelRetries;}
  public void setMaxTransientModelRetries(int value){if(value<0||value>3)throw new IllegalArgumentException("Transient model retries must be 0 to 3");maxTransientModelRetries=value;}
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
