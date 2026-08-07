package dev.mikoto2000.rei.skills;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.skills")
public class AgentSkillsProperties {

  private boolean enabled = true;
  private List<String> directories = new ArrayList<>(List.of("${user.dir}/.rei/skills"));
  private int maxSelected = 3;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public List<String> getDirectories() {
    return directories;
  }

  public void setDirectories(List<String> directories) {
    this.directories = directories == null ? new ArrayList<>() : new ArrayList<>(directories);
  }

  public int getMaxSelected() {
    return Math.max(1, maxSelected);
  }

  public void setMaxSelected(int maxSelected) {
    this.maxSelected = maxSelected;
  }
}
