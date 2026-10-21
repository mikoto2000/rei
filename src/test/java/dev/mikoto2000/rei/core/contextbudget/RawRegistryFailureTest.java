package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.storage.StorageObjectRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 加工前の登録台帳失敗情報の結果と状態の契約を検証するテスト。 */
class RawRegistryFailureTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** モデルへ渡す文脈の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void registrationFailureDoesNotPublishOrAccumulateNewBodies() throws Exception {
    var registry = mock(StorageObjectRegistry.class);
    when(registry.serialized(any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
    doThrow(new IllegalStateException("database failure"))
        .when(registry)
        .registerRawWithBody(
            anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean());
    var store = new RawToolResultStore(dir, registry);
    assertThatThrownBy(() -> store.save("chat", "run", "reader", "call", "original"))
        .hasMessageContaining("database failure");
    try (var paths = Files.walk(dir)) {
      assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();
    }
  }
}
