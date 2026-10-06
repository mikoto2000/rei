package dev.mikoto2000.rei.llm;

/** Lexical budget propagation for synchronous providers, explicitly captured at worker boundaries. */
public final class ModelCallBudgetScope implements AutoCloseable {
  private static final ThreadLocal<ModelCallBudget> CURRENT=new ThreadLocal<>();
  private final ModelCallBudget previous;
  private boolean closed;
  private ModelCallBudgetScope(ModelCallBudget budget) {
    previous=CURRENT.get();if(budget==null)CURRENT.remove();else CURRENT.set(budget);
  }
  public static ModelCallBudgetScope open(ModelCallBudget budget){return new ModelCallBudgetScope(budget);}
  public static ModelCallBudget current(){return CURRENT.get();}
  public void close(){if(closed)return;closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
