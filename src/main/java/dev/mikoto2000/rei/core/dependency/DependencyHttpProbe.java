package dev.mikoto2000.rei.core.dependency;
/** Read-only network observation port. Authorization belongs to the caller. */
@FunctionalInterface public interface DependencyHttpProbe {
  DependencyObservation probe(String id,String url,int expectedStatus);
}
