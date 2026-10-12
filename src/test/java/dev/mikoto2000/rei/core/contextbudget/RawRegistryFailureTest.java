package dev.mikoto2000.rei.core.contextbudget;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
import java.nio.file.*;import java.util.function.Supplier;import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.storage.StorageObjectRegistry;
class RawRegistryFailureTest {
 @TempDir Path dir;
 @Test void registrationFailureDoesNotPublishOrAccumulateNewBodies()throws Exception {
  var registry=mock(StorageObjectRegistry.class);
  when(registry.serialized(any())).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());
  doThrow(new IllegalStateException("database failure")).when(registry).registerRawWithBody(anyString(),anyString(),anyString(),anyString(),anyBoolean(),anyBoolean());
  var store=new RawToolResultStore(dir,registry);
  assertThatThrownBy(()->store.save("chat","run","reader","call","original")).hasMessageContaining("database failure");
  try(var paths=Files.walk(dir)){assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();}
 }
}
