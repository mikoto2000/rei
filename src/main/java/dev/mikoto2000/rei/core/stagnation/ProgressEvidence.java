package dev.mikoto2000.rei.core.stagnation;
/** Immutable explanation; revision is an observed state digest, never a completion claim. */
public record ProgressEvidence(ProgressEvent kind, String description, String source, String revision) {
  public ProgressEvidence(ProgressEvent kind,String description,String source){this(kind,description,source,null);}
}
