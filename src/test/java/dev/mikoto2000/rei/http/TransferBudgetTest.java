package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TransferBudgetTest {
  @Test void combinedTransfersCannotExceedEitherLimit() {
    var budget = new TransferBudget(10, 20);
    budget.wire(6); budget.wire(4); budget.decoded(15); budget.decoded(5);
    assertEquals(10, budget.wireBytes()); assertEquals(20, budget.decodedBytes());
    assertEquals(HttpFetchException.Code.TRANSFER_BUDGET, assertThrows(HttpFetchException.class, () -> budget.wire(1)).code());
    assertThrows(HttpFetchException.class, () -> budget.decoded(1)); assertEquals(20, budget.decodedBytes());
  }
  @Test void parallelReservationsCannotOverrunTheSharedLimit() throws Exception {
    var budget = new TransferBudget(100, 100);
    try (var workers = Executors.newFixedThreadPool(3)) {
      var futures = new java.util.ArrayList<Future<?>>();
      for (int i = 0; i < 3; i++) futures.add(workers.submit(() -> {
        for (int j = 0; j < 100; j++) try { budget.wire(1); } catch (HttpFetchException exhausted) { break; }
      }));
      for (var future : futures) future.get();
    }
    assertEquals(100, budget.wireBytes());
  }
  @Test void decodedBudgetIsCheckedBeforeDecompressionWrites() {
    var budget = new TransferBudget(10, 2);
    assertThrows(HttpFetchException.class, () -> BoundedBody.decode(new byte[] {1, 2, 3}, null, 10, 10, () -> {}, budget::decoded));
    assertEquals(0, budget.decodedBytes());
  }
  @Test void scopeRestoresAndCarriesTheSameBudgetAcrossWorkers() throws Exception {
    var budget = new TransferBudget(10, 20); assertNull(TransferScope.current());
    try (var scope = TransferScope.enter(budget); var worker = Executors.newSingleThreadExecutor()) {
      var captured = TransferScope.current();
      worker.submit(() -> { try (var transferred = TransferScope.enter(captured)) { TransferScope.current().wire(4); } }).get();
      assertSame(budget, TransferScope.current()); assertEquals(4, budget.wireBytes());
    }
    assertNull(TransferScope.current());
  }
}
