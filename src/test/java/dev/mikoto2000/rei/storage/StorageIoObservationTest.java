package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class StorageIoObservationTest {
  @TempDir Path root;
  @Test void countsSuccessfulExplicitForcesAndRestoresNestedObservation() throws Exception {
    Path file=Files.writeString(root.resolve("fixture"),"fixture");
    var outer=new StorageIoObservation();var inner=new StorageIoObservation();
    try(var scope=outer.observe()) {
      StorageBackup.force(file);
      try(var nested=inner.observe()) {StorageBackup.force(file);}
      assertThatThrownBy(()->StorageBackup.force(root.resolve("missing"))).isInstanceOf(java.io.IOException.class);
      StorageBackup.force(file);
    }
    StorageBackup.force(file);
    assertThat(outer.forces.sum()).isEqualTo(2);assertThat(inner.forces.sum()).isEqualTo(1);
  }
}
