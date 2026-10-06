package dev.mikoto2000.rei.core.dependency;
/** Read-only network observation port. Authorization belongs to the caller. */
@FunctionalInterface public interface DependencyHttpProbe {
  DependencyObservation probe(String id,String url,int expectedStatus);
  default DependencyObservation probeBody(String id,String url,int expectedStatus,String sha256) {
    return new DependencyObservation(id,DependencyState.BLOCKED,"http_body_verification_unsupported");
  }
}
