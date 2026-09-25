package dev.mikoto2000.rei.activity;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class ProjectAliasStoreTest {
  @TempDir Path directory;
  @Test void reloadsOnDemandAndRetainsLastGoodSnapshotOnConflict() throws Exception {
    var file=directory.resolve("aliases.yaml");var store=new ProjectAliasStore(file);
    assertThat(store.get().normalize("sensorvoice-input")).isEqualTo("sensorvoice-input");
    Files.writeString(file,"projectAliases:\n  sensevoice-input: [sensorvoice-input, sensevoiceinput]\n");
    assertThat(store.get().normalize("sensorvoice-input")).isEqualTo("sensevoice-input");
    Files.writeString(file,"projectAliases:\n  first: [same]\n  second: [same]\n");
    assertThat(store.get().normalize("sensorvoice-input")).isEqualTo("sensevoice-input");
    Files.writeString(file,"projectAliases:\n  voice: [sensorvoice-input]\n");
    assertThat(store.get().normalize("sensorvoice-input")).isEqualTo("voice");
    Files.delete(file);assertThat(store.get().normalize("sensorvoice-input")).isEqualTo("sensorvoice-input");
  }
  @Test void invalidYamlDoesNotReplaceValidAliases() throws Exception {
    var file=directory.resolve("aliases.yaml");
    Files.writeString(file,"projectAliases:\n  rei: [rei-dev]\n");var store=new ProjectAliasStore(file);store.get();
    for(var invalid:java.util.List.of("projectAliases:\n  '': [x]","projectAliases:\n  a: ['']","projectAliases:\n  a: [x, x]",
        "projectAliases:\n  a: [x]\n  a: [y]","projectAliases: [x]","other: value","!!java.lang.Runtime {}")) {
      Files.writeString(file,invalid);assertThat(store.get().normalize("rei-dev")).isEqualTo("rei");
    }
  }
}
