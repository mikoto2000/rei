package dev.mikoto2000.rei.computer;

public record ComputerObservationRequest(
    Integer maxDepth,
    Integer maxElements,
    Boolean activeWindowOnly,
    Boolean visibleOnly) {

  public int effectiveMaxDepth(int defaultValue) {
    return maxDepth == null ? defaultValue : Math.max(0, maxDepth);
  }

  public int effectiveMaxElements(int defaultValue) {
    return maxElements == null ? defaultValue : Math.max(1, maxElements);
  }

  public boolean effectiveActiveWindowOnly(boolean defaultValue) {
    return activeWindowOnly == null ? defaultValue : activeWindowOnly;
  }

  public boolean effectiveVisibleOnly(boolean defaultValue) {
    return visibleOnly == null ? defaultValue : visibleOnly;
  }
}
