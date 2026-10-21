package dev.mikoto2000.rei.core.dependency;

/**
 * 外部条件の待機に必要な依存条件利用可否の確認を扱う。
 *
 * <p>External state observation port; implementations do not complete tasks or execute actions.
 */
@FunctionalInterface
public interface DependencyProbe {
  /** 外部条件の待機で用いる利用可否の確認を返す。 */
  DependencyObservation probe(PersistentDependencyRepository.Entry entry);
}
