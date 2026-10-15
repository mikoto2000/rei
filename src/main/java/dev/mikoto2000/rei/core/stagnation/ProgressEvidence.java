package dev.mikoto2000.rei.core.stagnation;

/** Immutable explanation; revision and acquisition progress never imply completed work. */
public record ProgressEvidence(
    ProgressEvent kind,
    String description,
    String source,
    String revision,
    boolean acquisitionProgress) {
  public ProgressEvidence(ProgressEvent kind, String description, String source, String revision) {
    this(kind, description, source, revision, false);
  }

  public ProgressEvidence(ProgressEvent kind, String description, String source) {
    this(kind, description, source, null, false);
  }

  /** An exact, previously unseen delivered range, rather than a new query or changing metadata. */
  public static ProgressEvidence acquiredRange(String description, String source, String revision) {
    return new ProgressEvidence(ProgressEvent.NEW_INFORMATION, description, source, revision, true);
  }
}
