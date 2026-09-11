package dev.mikoto2000.rei.subagent;

import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;

@ConfigurationProperties(prefix = "rei.subagents")
public class SubAgentProperties {
  private Path directory = ReiDataDirectory.current().resolve("config/subagents");
  private Set<String> models = Set.of();
  public Path getDirectory() { return directory; }
  public void setDirectory(Path directory) { this.directory = directory; }
  public Set<String> getModels() { return models; }
  public void setModels(Set<String> models) { this.models = Set.copyOf(models); }
}
