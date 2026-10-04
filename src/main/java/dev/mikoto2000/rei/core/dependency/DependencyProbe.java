package dev.mikoto2000.rei.core.dependency;
/** External state observation port; implementations do not complete tasks or execute actions. */
@FunctionalInterface public interface DependencyProbe {
  DependencyObservation probe(PersistentDependencyRepository.Entry entry);
}
