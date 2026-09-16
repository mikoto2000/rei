export interface HistoryPage<T> {
  items: T[];
  nextCursor: string | null;
}
export interface HistoryState<T> extends HistoryPage<T> {
  status: "LOADING" | "LOADED" | "EMPTY" | "ERROR";
  loadingInitial: boolean;
  loadingMore: boolean;
  refreshing: boolean;
  error: string | null;
  stale: boolean;
}
const empty = <T>(): HistoryState<T> => ({
  items: [],
  nextCursor: null,
  status: "EMPTY",
  loadingInitial: false,
  loadingMore: false,
  refreshing: false,
  error: null,
  stale: false,
});

/** Context generations prevent a late server/filter/session response from replacing newer state. */
export class HistoryPager<T, C> {
  private state = empty<T>();
  private context: C | null = null;
  private generation = 0;
  private inFlight = false;
  private retryMore = false;
  private listeners = new Set<() => void>();
  constructor(
    private load: (
      context: C,
      cursor: string | null,
    ) => Promise<HistoryPage<T>>,
    private identity: (row: T) => string,
    private duplicate: (count: number) => void = () => {},
  ) {}
  snapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private publish(state: HistoryState<T>) {
    this.state = state;
    this.listeners.forEach((listener) => listener());
  }
  clear() {
    this.generation++;
    this.context = null;
    this.inFlight = false;
    this.publish(empty<T>());
  }
  async reset(context: C) {
    this.generation++;
    this.context = context;
    this.inFlight = false;
    this.publish(empty<T>());
    await this.fetch(false);
  }
  async refresh() {
    if (this.context === null) return;
    this.generation++;
    this.inFlight = false;
    this.publish({ ...this.state, nextCursor: null });
    await this.fetch(false);
  }
  async more() {
    if (
      this.context !== null &&
      this.state.nextCursor !== null &&
      !this.inFlight
    )
      await this.fetch(true);
  }
  async retry() {
    if (this.inFlight) return;
    if (this.retryMore && this.state.error !== "InvalidCursor")
      await this.more();
    else await this.refresh();
  }
  private async fetch(more: boolean) {
    if (this.context === null || this.inFlight) return;
    const context = this.context,
      generation = this.generation,
      cursor = more ? this.state.nextCursor : null;
    this.inFlight = true;
    this.retryMore = more;
    this.publish({
      ...this.state,
      status: this.state.items.length ? "LOADED" : "LOADING",
      loadingInitial: !more && !this.state.items.length,
      loadingMore: more,
      refreshing: !more && !!this.state.items.length,
      error: null,
    });
    try {
      const page = await this.load(context, cursor);
      if (generation !== this.generation) return;
      if (more && page.nextCursor !== null && page.nextCursor === cursor)
        throw "InvalidCursor";
      const rows = new Map<string, T>();
      let duplicates = 0;
      if (more)
        this.state.items.forEach((item) => rows.set(this.identity(item), item));
      for (const item of page.items) {
        const id = this.identity(item);
        if (rows.has(id)) duplicates++;
        rows.set(id, item);
      }
      if (duplicates) this.duplicate(duplicates);
      const items = [...rows.values()];
      this.publish({
        ...empty<T>(),
        items,
        nextCursor: page.nextCursor,
        status: items.length ? "LOADED" : "EMPTY",
      });
    } catch (error) {
      if (generation !== this.generation) return;
      this.publish({
        ...this.state,
        status: "ERROR",
        loadingInitial: false,
        loadingMore: false,
        refreshing: false,
        error: typeof error === "string" ? error : "UnexpectedServerError",
        stale: this.state.items.length > 0,
      });
    } finally {
      if (generation === this.generation) this.inFlight = false;
    }
  }
}
