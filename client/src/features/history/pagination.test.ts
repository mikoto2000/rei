import { describe, it, expect, vi } from "vitest";
import { HistoryPager } from "./pagination";
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
};
type Row = { id: string };
describe("history paging behavior", () => {
  it("fetches initial and next pages, deduplicates and stops at null", async () => {
    const load = vi
      .fn()
      .mockResolvedValueOnce({
        items: [{ id: "a" }],
        nextCursor: "opaque:/+?=",
      })
      .mockResolvedValueOnce({
        items: [{ id: "a" }, { id: "b" }],
        nextCursor: null,
      });
    const warn = vi.fn();
    const pager = new HistoryPager<Row, string>(load, (row) => row.id, warn);
    await pager.reset("project-id");
    await pager.more();
    await pager.more();
    expect(load.mock.calls).toEqual([
      ["project-id", null],
      ["project-id", "opaque:/+?="],
    ]);
    expect(pager.snapshot().items).toEqual([{ id: "a" }, { id: "b" }]);
    expect(warn).toHaveBeenCalledWith(1);
  });
  it("prevents duplicate loads and discards results from an obsolete filter", async () => {
    const old = deferred<{ items: Row[]; nextCursor: string | null }>();
    const load = vi
      .fn()
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce({ items: [{ id: "new" }], nextCursor: null });
    const pager = new HistoryPager<Row, string>(load, (row) => row.id);
    const pending = pager.reset("old");
    await pager.more();
    await pager.reset("new");
    old.resolve({ items: [{ id: "old" }], nextCursor: "old-cursor" });
    await pending;
    expect(pager.snapshot().items).toEqual([{ id: "new" }]);
    expect(load).toHaveBeenCalledTimes(2);
  });
  it("refresh resets cursor, retains stale items on errors, and retry is explicit", async () => {
    const load = vi
      .fn()
      .mockResolvedValueOnce({ items: [{ id: "a" }], nextCursor: "next" })
      .mockRejectedValueOnce("ServerUnreachable")
      .mockResolvedValueOnce({ items: [{ id: "b" }], nextCursor: null });
    const pager = new HistoryPager<Row, string>(load, (row) => row.id);
    await pager.reset("p");
    await pager.refresh();
    expect(pager.snapshot()).toMatchObject({
      items: [{ id: "a" }],
      nextCursor: null,
      error: "ServerUnreachable",
      stale: true,
      status: "ERROR",
    });
    await pager.retry();
    expect(load.mock.calls[2]).toEqual(["p", null]);
    expect(pager.snapshot().items).toEqual([{ id: "b" }]);
  });
  it("load-more failure keeps items and cursor then retries the same opaque page", async () => {
    const load = vi
      .fn()
      .mockResolvedValueOnce({ items: [{ id: "a" }], nextCursor: "next" })
      .mockRejectedValueOnce("UnexpectedServerError")
      .mockResolvedValueOnce({ items: [{ id: "b" }], nextCursor: null });
    const pager = new HistoryPager<Row, string>(load, (row) => row.id);
    await pager.reset("p");
    await pager.more();
    expect(pager.snapshot().items).toEqual([{ id: "a" }]);
    await pager.retry();
    expect(load.mock.calls[2]).toEqual(["p", "next"]);
    expect(pager.snapshot().items).toHaveLength(2);
  });
  it("clears on context change, represents loading/empty, and suppresses repeated cursor loops", async () => {
    const next = deferred<{ items: Row[]; nextCursor: string | null }>();
    const load = vi
      .fn()
      .mockResolvedValueOnce({ items: [], nextCursor: null })
      .mockReturnValueOnce(next.promise)
      .mockResolvedValueOnce({ items: [], nextCursor: "same" });
    const pager = new HistoryPager<Row, string>(load, (row) => row.id);
    await pager.reset("a");
    expect(pager.snapshot().status).toBe("EMPTY");
    const pending = pager.reset("b");
    expect(pager.snapshot()).toMatchObject({
      items: [],
      loadingInitial: true,
      status: "LOADING",
    });
    next.resolve({ items: [{ id: "b" }], nextCursor: "same" });
    await pending;
    await pager.more();
    expect(pager.snapshot().error).toBe("InvalidCursor");
  });
});
