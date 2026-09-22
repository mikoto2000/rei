package dev.mikoto2000.rei.activity;

/** Evidence is optional. Success and extraction failure are independent opt-ins. */
public record ScreenshotPersistencePolicy(ActivityProperties properties) {
  public enum Outcome { SUCCESS, EXTRACTION_FAILURE }

  public boolean shouldSave(boolean duplicate, Outcome outcome) {
    if (duplicate || properties.getScreenshotRetentionDays() == 0) return false;
    return switch (outcome) {
      case SUCCESS -> properties.isKeepScreenshots();
      case EXTRACTION_FAILURE -> properties.isKeepOnExtractionFailure();
    };
  }
}
