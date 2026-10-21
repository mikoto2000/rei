package dev.mikoto2000.rei.activity.behavior;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.FileSystemResource;

/** 作業習慣機能の構成の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class BehaviorConfigurationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir java.nio.file.Path directory;

  /** 作業習慣の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void generatedConfigContainsBindableBehaviorDefaults() {
    var file =
        new dev.mikoto2000.rei.config.ExternalConfigFileService(directory)
            .initializeConfigFile(false);
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new FileSystemResource(file));
    var source = new MapConfigurationPropertySource();
    yaml.getObject().forEach((k, v) -> source.put(k.toString(), v));
    var properties =
        new Binder(source).bind("rei.activity.behavior", BehaviorProperties.class).get();
    properties.validate();
    assertFalse(properties.isEnabled());
    assertEquals(120, properties.getContinuous().getStrongWarningMinutes());
    assertEquals(30, properties.getWindows().getShortWindow().getMinimumObservedMinutes());
    assertEquals(.6, properties.getWindows().getLongWindow().getRatio());
    assertEquals(60, properties.getCooldown().getNoticeMinutes());
    assertEquals(60, properties.getInterruption().getNoiseToleranceSeconds());
    assertTrue(yaml.getObject().containsKey("rei.llm.features.activity-behavior.model"));
  }

  /** 作業習慣の支援で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void overridesBindAndUnsafeCategoriesAreRejected() {
    var source =
        new MapConfigurationPropertySource(
            Map.of(
                "rei.activity.behavior.enabled",
                "true",
                "rei.activity.behavior.continuous.notice-minutes",
                "15",
                "rei.activity.behavior.windows.short-window.ratio",
                "0.75"));
    var p = new Binder(source).bind("rei.activity.behavior", BehaviorProperties.class).get();
    p.validate();
    assertTrue(p.isEnabled());
    assertEquals(15, p.getContinuous().getNoticeMinutes());
    assertEquals(.75, p.getWindows().getShortWindow().getRatio());
    p.setEntertainmentCategories(Set.of("unknown"));
    assertThrows(IllegalArgumentException.class, p::validate);
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledSchedulerDoesNotSubmitAndBusyWorkerDoesNotQueue() {
    var service = mock(BehaviorService.class);
    var executor = mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
    var job = new BehaviorConfiguration.BehaviorJob(service, executor);
    job.poll();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(executor);
    when(service.enabled()).thenReturn(true);
    doThrow(new org.springframework.core.task.TaskRejectedException("busy"))
        .when(executor)
        .execute(any(Runnable.class));
    assertDoesNotThrow(job::poll);
    verify(service, never()).tick();
  }
}
