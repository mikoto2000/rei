package dev.mikoto2000.rei.core.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class RoundRobinSelector<T> {

  private final List<T> delegates;
  private final AtomicInteger index = new AtomicInteger(0);

  RoundRobinSelector(List<T> delegates) {
    if (delegates == null || delegates.isEmpty()) {
      throw new IllegalArgumentException("delegates must not be empty");
    }
    this.delegates = List.copyOf(delegates);
  }

  T next() {
    int current = index.getAndUpdate(value -> value == Integer.MAX_VALUE ? 0 : value + 1);
    return delegates.get(Math.floorMod(current, delegates.size()));
  }

  T first() {
    return delegates.getFirst();
  }

  int size() {
    return delegates.size();
  }
}
