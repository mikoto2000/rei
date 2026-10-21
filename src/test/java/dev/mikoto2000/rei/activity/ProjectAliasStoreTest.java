package dev.mikoto2000.rei.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** プロジェクト別名保存先の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectAliasStoreTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 作業状況の把握で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void reloadsOnDemandAndRetainsLastGoodSnapshotOnConflict() throws Exception {
    var file = directory.resolve("aliases.yaml");
    var store = new ProjectAliasStore(file);
    assertThat(store.get().normalize("sensorvoice-input")).isEqualTo("sensorvoice-input");
    Files.writeString(
        file, "projectAliases:\n  sensevoice-input: [sensorvoice-input, sensevoiceinput]\n");
    assertThat(store.get().normalize("sensorvoice-input")).isEqualTo("sensevoice-input");
    Files.writeString(file, "projectAliases:\n  first: [same]\n  second: [same]\n");
    assertThat(store.get().normalize("sensorvoice-input")).isEqualTo("sensevoice-input");
    Files.writeString(file, "projectAliases:\n  voice: [sensorvoice-input]\n");
    assertThat(store.get().normalize("sensorvoice-input")).isEqualTo("voice");
    Files.delete(file);
    assertThat(store.get().normalize("sensorvoice-input")).isEqualTo("sensorvoice-input");
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidYamlDoesNotReplaceValidAliases() throws Exception {
    var file = directory.resolve("aliases.yaml");
    Files.writeString(file, "projectAliases:\n  rei: [rei-dev]\n");
    var store = new ProjectAliasStore(file);
    store.get();
    for (var invalid :
        java.util.List.of(
            "projectAliases:\n  '': [x]",
            "projectAliases:\n  a: ['']",
            "projectAliases:\n  a: [x, x]",
            "projectAliases:\n  a: [x]\n  a: [y]",
            "projectAliases: [x]",
            "other: value",
            "!!java.lang.Runtime {}")) {
      Files.writeString(file, invalid);
      assertThat(store.get().normalize("rei-dev")).isEqualTo("rei");
    }
  }
}
