import {
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import type { Command } from "../../tauri/commands";
import type {
  SessionSummary,
  ConversationTurn,
  Conversation,
  Run,
} from "../../entities/models";
import { HistoryPager } from "./pagination";
import { SessionSelection } from "./selection";
type ListContext = { serverId: string; projectId: string | null };
type TurnContext = { serverId: string; sessionId: string };
// Counts only: never log IDs, cursor values, titles, messages or credentials.
const duplicates = (count: number) =>
  console.warn(`History page repeated ${count} identities`);
export function useSessionList(
  call: Command,
  serverId: string | null,
  enabled: boolean,
) {
  const [filter, setFilter] = useState<{
    server: string | null;
    project: string | null;
  }>({ server: null, project: null });
  const projectId = filter.server === serverId ? filter.project : null;
  const pager = useMemo(
    () =>
      new HistoryPager<SessionSummary, ListContext>(
        (context, cursor) =>
          call("session_list", { ...context, limit: 50, cursor }),
        (row) => row.sessionId,
        duplicates,
      ),
    [call],
  );
  const state = useSyncExternalStore(pager.subscribe, pager.snapshot);
  useEffect(() => {
    if (serverId && enabled) void pager.reset({ serverId, projectId });
    else pager.clear();
    return () => pager.clear();
  }, [pager, serverId, projectId, enabled]);
  return {
    pager,
    state,
    projectId,
    setProject: (project: string | null) =>
      setFilter({ server: serverId, project }),
  };
}
export function useSessionSelection(call: Command) {
  const selection = useMemo(() => new SessionSelection(call), [call]);
  const state = useSyncExternalStore(selection.subscribe, selection.snapshot);
  useEffect(() => () => selection.clear(), [selection]);
  return { selection, state };
}
export function useTurnHistory(
  call: Command,
  conversation: Conversation | undefined,
) {
  const pager = useMemo(
    () =>
      new HistoryPager<ConversationTurn, TurnContext>(
        (context, cursor) =>
          call("session_turns", { ...context, limit: 50, cursor }),
        (row) => row.runId,
        duplicates,
      ),
    [call],
  );
  const state = useSyncExternalStore(pager.subscribe, pager.snapshot);
  const serverId = conversation?.serverProfileId,
    sessionId = conversation?.sessionId;
  useEffect(() => {
    if (serverId && sessionId) void pager.reset({ serverId, sessionId });
    else pager.clear();
    return () => pager.clear();
  }, [pager, serverId, sessionId]);
  return { pager, state };
}
export function useTerminalHistoryRefresh(
  runs: Run[],
  server: string | null,
  conversation: Conversation | undefined,
  list: HistoryPager<SessionSummary, ListContext>,
  turns: HistoryPager<ConversationTurn, TurnContext>,
  reload: () => Promise<void>,
) {
  const seen = useRef(new Set<string>());
  useEffect(() => {
    let refreshList = false,
      refreshTurns = false;
    for (const run of runs) {
      if (run.status === "RUNNING" || run.status === "QUEUED") continue;
      const key = JSON.stringify([run.serverId, run.runId]);
      if (seen.current.has(key)) continue;
      seen.current.add(key);
      refreshList ||= run.serverId === server;
      refreshTurns ||=
        run.serverId === conversation?.serverProfileId &&
        run.sessionId === conversation?.sessionId;
    }
    if (refreshList) void list.refresh();
    if (refreshTurns) {
      void turns.refresh();
      void reload().catch(() => {});
    }
  }, [
    runs,
    server,
    conversation?.serverProfileId,
    conversation?.sessionId,
    list,
    turns,
    reload,
  ]);
}
