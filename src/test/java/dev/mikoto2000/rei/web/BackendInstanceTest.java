package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.launcher.BackendEndpointStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** バックエンド起動単位の識別子の結果と状態の契約を検証するテスト。 */
class BackendInstanceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void publishesOnlyAfterReadyAndRemovesOnlyOwnEndpoint() throws Exception {
    var store = new BackendEndpointStore(root);
    var instance = new BackendInstance(store);
    assertThrows(IllegalStateException.class, instance::get);
    instance.ready(12345);
    var identity = instance.get();
    assertEquals(identity, store.read().orElseThrow());
    assertEquals("http://127.0.0.1:12345", identity.baseUrl());
    assertFalse(instance.matches("wrong"));
    assertTrue(instance.matches(identity.instanceId()));
    instance.close();
    assertTrue(store.read().isEmpty());
  }
}
