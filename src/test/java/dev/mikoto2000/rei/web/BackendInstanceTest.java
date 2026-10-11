package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.launcher.BackendEndpointStore;

class BackendInstanceTest {
  @TempDir Path root;
  @Test void publishesOnlyAfterReadyAndRemovesOnlyOwnEndpoint() throws Exception {
    var store = new BackendEndpointStore(root);
    var instance = new BackendInstance(store);
    assertThrows(IllegalStateException.class,instance::get);
    instance.ready(12345);
    var identity = instance.get();
    assertEquals(identity,store.read().orElseThrow());
    assertEquals("http://127.0.0.1:12345",identity.baseUrl());
    assertFalse(instance.matches("wrong"));
    assertTrue(instance.matches(identity.instanceId()));
    instance.close();
    assertTrue(store.read().isEmpty());
  }
}
