package dev.mikoto2000.rei.llm.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in until retention, resume and transport regression checks have passed. */
@ConfigurationProperties("rei.llm.tool-discovery")
public class ToolDiscoveryProperties {
  private boolean enabled;
  private int maxActiveTools = 128;
  private int searchMaxResults = 3;
  private int maxAddedToolsPerSearch = 24;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean value) {
    enabled = value;
  }

  public int getMaxActiveTools() {
    return maxActiveTools;
  }

  public void setMaxActiveTools(int value) {
    if (value < 1 || value > 128)
      throw new IllegalArgumentException("max-active-tools must be 1..128");
    maxActiveTools = value;
  }

  public int getSearchMaxResults() {
    return searchMaxResults;
  }

  public void setSearchMaxResults(int value) {
    if (value < 1 || value > 5)
      throw new IllegalArgumentException("search-max-results must be 1..5");
    searchMaxResults = value;
  }

  public int getMaxAddedToolsPerSearch() {
    return maxAddedToolsPerSearch;
  }

  public void setMaxAddedToolsPerSearch(int value) {
    if (value < 1 || value > 128)
      throw new IllegalArgumentException("max-added-tools-per-search must be 1..128");
    maxAddedToolsPerSearch = value;
  }
}
