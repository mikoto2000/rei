package dev.mikoto2000.rei.artifact;
public final class ArtifactException extends RuntimeException {
  public enum Code { CAPACITY,UNAVAILABLE,MISSING,STALE,EXPIRED,DELETED,UNSAFE_STORAGE,STORAGE_FAILURE }
  private final Code code;
  public ArtifactException(Code code){super("Artifact: "+code);this.code=code;}
  public Code code(){return code;}
}
