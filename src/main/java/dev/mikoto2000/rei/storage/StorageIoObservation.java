package dev.mikoto2000.rei.storage;

import java.util.concurrent.atomic.LongAdder;

/** Optional in-memory application I/O probe. These counters are not OS fsync counters. */
final class StorageIoObservation {
  private static final ThreadLocal<StorageIoObservation> CURRENT=new ThreadLocal<>();
  final LongAdder forces=new LongAdder(),writes=new LongAdder(),bytes=new LongAdder();
  interface Scope extends AutoCloseable { @Override void close(); }
  Scope observe() {
    var previous=CURRENT.get();CURRENT.set(this);
    return ()->{if(previous==null)CURRENT.remove();else CURRENT.set(previous);};
  }
  static void forced() {var probe=CURRENT.get();if(probe!=null)probe.forces.increment();}
  static void written(int bytes) {var probe=CURRENT.get();if(probe!=null){probe.writes.increment();probe.bytes.add(bytes);}}
}
