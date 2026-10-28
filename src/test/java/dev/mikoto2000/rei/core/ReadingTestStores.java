package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.tooldelivery.ReadingStateStore;
import java.time.Clock;

/** Same SQL port binding as production, with test-owned data directories and clocks. */
final class ReadingTestStores {
  private ReadingTestStores() {}
  static ReadingStateStore store(StorageDatabase database) { return store(database, Clock.systemUTC()); }
  static ReadingStateStore store(StorageDatabase database, Clock clock) {
    return new ReadingStateStore((write, work) -> write ? database.transaction(work::run) : database.read(work::run), clock);
  }
  static dev.mikoto2000.rei.tooldelivery.ReadingDelivery delivery(ReadingStateStore store,
      dev.mikoto2000.rei.core.contextbudget.RawToolResultStore raw) {
    return new dev.mikoto2000.rei.tooldelivery.ReadingDelivery(store,
        new dev.mikoto2000.rei.tooldelivery.ReadingRawAccess(raw::save, raw::readingBody,
            dev.mikoto2000.rei.core.contextbudget.RawToolResultTools::readingPage), Tools::readingPath);
  }
}
