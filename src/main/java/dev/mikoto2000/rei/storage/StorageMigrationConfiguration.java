package dev.mikoto2000.rei.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

/** 保存移行の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
public class StorageMigrationConfiguration {
  /** データ保存の共通処理で用いる保存起動時実行可否の判断を返す。 */
  @Bean
  public static StartupGate storageStartupGate() {
    return new StartupGate();
  }

  /** データ保存の共通処理で用いる保存対象データ登録台帳を返す。 */
  @Bean
  public StorageObjectRegistry storageObjectRegistry(Environment environment) {
    return new StorageObjectRegistry(
        Path.of(
            environment.getProperty(
                "rei.data-dir",
                dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().toString())));
  }

  /** データ保存の共通処理で用いる保持期間計画の作成を返す。 */
  @Bean
  public RetentionPlanner retentionPlanner(StorageObjectRegistry registry) {
    return new RetentionPlanner(registry);
  }

  /** データ保存の共通処理で用いる保持期間実行先を返す。 */
  @Bean
  public RetentionExecutor retentionExecutor(
      StorageObjectRegistry registry, RetentionPlanner planner) {
    return new RetentionExecutor(registry, planner);
  }

  /** データ保存の共通処理で用いる管理対象作業状況ログを返す。 */
  @Bean
  public ManagedActivityLog managedActivityLog(StorageObjectRegistry registry) {
    return new ManagedActivityLog(registry);
  }

  /** データ保存の共通処理で用いる保存保守を返す。 */
  @Bean
  public StorageMaintenance storageMaintenance(StorageObjectRegistry registry) {
    return new StorageMaintenance(registry);
  }

  /** データ保存の共通処理で用いる自動保持期間を返す。 */
  @Bean
  public AutomaticRetention automaticRetention(
      StorageObjectRegistry registry, RetentionPlanner planner, RetentionExecutor executor) {
    return new AutomaticRetention(registry, planner, executor);
  }

  /**
   * データ保存の共通処理に必要な起動時実行可否の判断を扱う。
   *
   * <p>Same-version contexts in one JVM share a ready lease; another process cannot migrate
   * underneath them.
   */
  public static final class StartupGate
      implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {
    /** 開始。 */
    private static final Map<Path, Shared> OPEN = new HashMap<>();

    /** 実行環境。 */
    private Environment environment;

    /** データ保存の共通処理に必要な共有を扱う。 */
    private static final class Shared {
      /** 処理の調整。 */
      final StorageMigrationCoordinator coordinator;

      /** 更新履歴の保存利用期間。 */
      final AutoCloseable walLifetime;

      /** 利用者一覧。 */
      int users = 1;

      /** 指定された値や利用先を使って、Sharedを初期化する。 */
      Shared(StorageMigrationCoordinator coordinator, AutoCloseable walLifetime) {
        this.coordinator = coordinator;
        this.walLifetime = walLifetime;
      }
    }

    /** データ保存の共通処理で使用する実行環境を設定する。 */
    @Override
    public void setEnvironment(Environment environment) {
      this.environment = environment;
    }

    /** 他の補助処理と組み合わせる際の適用順序を返す。 */
    @Override
    public int getOrder() {
      return HIGHEST_PRECEDENCE;
    }

    /** データ保存の共通処理に関する投稿プロセス機能の構成要素生成元を処理する。 */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory)
        throws BeansException {
      Path root =
          Path.of(
                  environment.getProperty(
                      "rei.data-dir",
                      dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().toString()))
              .toAbsolutePath()
              .normalize();
      try {
        if (!(factory instanceof DefaultListableBeanFactory standard))
          throw new IOException(
              "Unsupported bean factory; storage gate cannot guarantee shutdown lease release");
        Shared shared;
        synchronized (OPEN) {
          shared = OPEN.get(root);
          if (shared == null) {
            var coordinator = new StorageMigrationCoordinator(root);
            try {
              coordinator.prepare();
              shared = new Shared(coordinator, new StorageDatabase(root).keepWalOpen());
            } catch (Exception error) {
              coordinator.close();
              throw error;
            }
            OPEN.put(root, shared);
          } else {
            shared.coordinator.verifyCurrentReadyVersion();
            shared.users++;
          }
        }
        try {
          standard.registerSingleton("storageMigrationLease", shared.coordinator);
          standard.registerDisposableBean("storageMigrationLease", () -> release(root));
        } catch (Exception error) {
          release(root);
          throw error;
        }
      } catch (Exception error) {
        throw new BeanInitializationException(
            "Storage migration gate stopped normal startup for " + root, error);
      }
    }

    /** 後続の処理が利用できるよう、起動時実行可否の判断を解放する。 */
    private static void release(Path root) throws Exception {
      synchronized (OPEN) {
        var current = OPEN.get(root);
        if (current != null && --current.users == 0) {
          OPEN.remove(root);
          try {
            current.walLifetime.close();
          } finally {
            current.coordinator.close();
          }
        }
      }
    }
  }
}
