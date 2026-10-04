package dev.mikoto2000.rei.core.dependency;

public record DependencyObservation(String id,DependencyState state,String detail) {
  public DependencyObservation {
    if(id==null||id.isBlank()||state==null)throw new IllegalArgumentException("Dependency identity and state required");
  }
}
