package dev.mikoto2000.rei.http;

/** Explicit aggregate budget propagation, independent of a waiter's cancellation context. */
public final class TransferScope implements AutoCloseable {
  private static final ThreadLocal<TransferBudget> CURRENT = new ThreadLocal<>();
  private final TransferBudget previous;
  private TransferScope(TransferBudget budget) { previous = CURRENT.get(); if (budget == null) CURRENT.remove(); else CURRENT.set(budget); }
  public static TransferBudget current() { return CURRENT.get(); }
  public static TransferScope enter(TransferBudget budget) { return new TransferScope(budget); }
  public static void wire(int bytes) { var budget = current(); if (budget != null) budget.wire(bytes); }
  public static void decoded(int bytes) { var budget = current(); if (budget != null) budget.decoded(bytes); }
  public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
