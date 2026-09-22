import { useCallback, useEffect, useState } from "react";
import { command, type Command } from "./tauri/commands";
import { events, type Events } from "./tauri/events";
import {
  activeRuns,
  errorText,
  mergeRun,
  type Snapshot,
  type Run,
  type Connection,
  type Project,
  type Conversation,
} from "./entities/models";
import { Chat } from "./features/chat/Chat";
import { Settings } from "./features/settings/Settings";
import { useUserAvatar } from "./features/settings/userAvatar";
import { Avatar } from "./shared/Avatar";
import { SessionList } from "./features/history/SessionList";
import {
  useSessionList,
  useSessionSelection,
  useTurnHistory,
  useTerminalHistoryRefresh,
} from "./features/history/useHistory";
const initial: Snapshot = {
  servers: [],
  selectedServer: null,
  conversations: [],
  runs: [],
  unlocked: false,
  notifications: false,
};
type Page = "conversations" | "new" | "chat" | "runs" | "settings";
export function App({
  call = command,
  subscriptions = events,
}: {
  call?: Command;
  subscriptions?: Events;
}) {
  const [data, setData] = useState(initial);
  const [userAvatar, setUserAvatar] = useUserAvatar();
  const [page, setPage] = useState<Page>("conversations");
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [chatErrors, setChatErrors] = useState<Record<string, string | null>>(
    {},
  );
  const [pending, setPending] = useState(false);
  const [sending, setSending] = useState<string[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [selectedRun, setSelectedRun] = useState<string>();
  const [connections, setConnections] = useState<Record<string, Connection>>(
    {},
  );
  const [projects, setProjects] = useState<Record<string, Project[]>>({});
  const [project, setProject] = useState("");
  const [loadingProjects, setLoadingProjects] = useState(false);
  const history = useSessionList(call, data.selectedServer, data.unlocked);
  const opening = useSessionSelection(call);
  const navigate = (destination: Page) => {
    opening.selection.clear();
    setPage(destination);
  };
  const accept = useCallback(
    (snapshot: Snapshot) =>
      setData((current) => ({
        ...snapshot,
        runs: current.runs.reduce(mergeRun, snapshot.runs),
      })),
    [],
  );
  const reload = useCallback(
    async () => accept(await call("app_snapshot", undefined)),
    [call, accept],
  );
  useEffect(() => {
    let disposed = false;
    const stops: (() => void)[] = [];
    const retain = (stop: () => void) => {
      if (disposed) stop();
      else stops.push(stop);
    };
    void (async () => {
      try {
        retain(
          await subscriptions.runs((run) => {
            if (!disposed)
              setData((d) => ({ ...d, runs: mergeRun(d.runs, run) }));
          }),
        );
        retain(
          await subscriptions.connection((connection) => {
            if (!disposed)
              setConnections((c) => ({
                ...c,
                [connection.serverId]: connection,
              }));
          }),
        );
        const snapshot = await call("app_snapshot", undefined);
        if (!disposed) {
          accept(snapshot);
          setReady(true);
          if (!snapshot.servers.length || !snapshot.unlocked)
            setPage("settings");
        }
      } catch {
        if (!disposed) {
          setError("NativeUnavailable");
          setReady(true);
        }
      }
    })();
    return () => {
      disposed = true;
      stops.forEach((stop) => stop());
    };
  }, [call, subscriptions, accept]);
  useEffect(() => {
    const serverId = data.selectedServer;
    let disposed = false;
    setProject("");
    if (!serverId || !data.unlocked) return;
    setLoadingProjects(true);
    void call("projects_list", { serverId })
      .then((list) => {
        if (!disposed) {
          setProjects((p) => ({ ...p, [serverId]: list }));
          setProject(list[0]?.id ?? "");
        }
      })
      .catch((e) => {
        if (!disposed) setError(typeof e === "string" ? e : "Unknown");
      })
      .finally(() => {
        if (!disposed) setLoadingProjects(false);
      });
    return () => {
      disposed = true;
    };
  }, [data.selectedServer, data.unlocked, call]);
  const perform = async (action: () => Promise<unknown>) => {
    setPending(true);
    setError(null);
    try {
      await action();
      await reload();
    } catch (e) {
      setError(typeof e === "string" ? e : "Unknown");
    } finally {
      setPending(false);
    }
  };
  const openConversation = (conversation: Conversation, runId?: string) => {
    opening.selection.clear();
    setSelected(conversation.localId);
    setSelectedRun(runId);
    setPage("chat");
    void perform(() =>
      call("conversation_select", { conversationId: conversation.localId }),
    );
  };
  const openSession = async (sessionId: string) => {
    if (!data.selectedServer) return;
    setSelected(null);
    setSelectedRun(undefined);
    setPage("chat");
    const conversation = await opening.selection.open(
      data.selectedServer,
      sessionId,
    );
    if (!conversation) return;
    setData((d) => ({
      ...d,
      conversations: [
        ...d.conversations.filter((c) => c.localId !== conversation.localId),
        conversation,
      ],
    }));
    setSelected(conversation.localId);
  };
  const projectName = (server: string, id: string) =>
    projects[server]?.find((p) => p.id === id)?.name ?? id;
  const selectedConversation = data.conversations.find(
    (c) => c.localId === selected,
  );
  const turns = useTurnHistory(
    call,
    page === "chat" ? selectedConversation : undefined,
  );
  const refreshMetadata = useCallback(async () => {
    if (selectedConversation?.sessionId) {
      await call("session_open", {
        serverId: selectedConversation.serverProfileId,
        sessionId: selectedConversation.sessionId,
      });
    }
    await reload();
  }, [
    call,
    selectedConversation?.serverProfileId,
    selectedConversation?.sessionId,
    reload,
  ]);
  useTerminalHistoryRefresh(
    data.runs,
    data.selectedServer,
    page === "chat" ? selectedConversation : undefined,
    history.pager,
    turns.pager,
    refreshMetadata,
  );
  const active = activeRuns(data.runs);
  const runAction = (
    name: "run_cancel" | "run_get" | "run_subscribe",
    run: Run,
  ) =>
    void perform(() =>
      call(name, { serverId: run.serverId, runId: run.runId }),
    );
  const send = async (message: string): Promise<boolean> => {
    if (!selectedConversation) return false;
    const id = selectedConversation.localId;
    setSending((s) => [...s, id]);
    setChatErrors((e) => ({ ...e, [id]: null }));
    try {
      const run = await call("chat_submit", { conversationId: id, message });
      setData((d) => ({ ...d, runs: mergeRun(d.runs, run) }));
      await reload();
      void history.pager.refresh();
      void turns.pager.refresh();
      return true;
    } catch (e) {
      setChatErrors((errors) => ({
        ...errors,
        [id]: typeof e === "string" ? e : "Unknown",
      }));
      await reload().catch(() => {});
      return false;
    } finally {
      setSending((s) => s.filter((c) => c !== id));
    }
  };
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            navigate("conversations");
          }}
        >
          <Avatar role="assistant" className="brand-avatar" />
          <div>
            rei<span>WORKSPACE CLIENT</span>
          </div>
        </a>
        <button
          className="new-chat"
          aria-label="新しい会話"
          onClick={() => {
            setError(null);
            navigate("new");
          }}
        >
          ＋ 新しい会話
        </button>
        <nav aria-label="メイン">
          <button
            className={
              page === "conversations" || page === "chat" ? "selected" : ""
            }
            onClick={() => navigate("conversations")}
          >
            ▤ Conversations <span>{history.state.items.length}</span>
          </button>
          <button
            className={page === "runs" ? "selected" : ""}
            onClick={() => navigate("runs")}
          >
            ◉ Active Runs <span>{active.length}</span>
          </button>
          <button
            className={page === "settings" ? "selected" : ""}
            onClick={() => navigate("settings")}
          >
            ⚙ Settings
          </button>
        </nav>
        <div className="sidebar-recent">
          <p className="eyebrow">RECENT CONVERSATIONS</p>
          {history.state.items.slice(0, 7).map((c) => (
            <button
              key={c.sessionId}
              onClick={() => void openSession(c.sessionId)}
              className={
                selectedConversation?.sessionId === c.sessionId &&
                page === "chat"
                  ? "selected"
                  : ""
              }
            >
              {c.title}
              <small>
                {projectName(data.selectedServer ?? "", c.projectId)}
              </small>
            </button>
          ))}
        </div>
        <div className="server-switch">
          <label>
            REI SERVER
            <select
              aria-label="選択中のサーバー"
              value={data.selectedServer ?? ""}
              disabled={pending}
              onChange={(e) => {
                opening.selection.clear();
                setSelected(null);
                setPage("conversations");
                void perform(() =>
                  call("server_select", { serverId: e.target.value }),
                );
              }}
            >
              <option value="" disabled>
                サーバーを選択
              </option>
              {data.servers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <small>
            <span className="connection-dot" />
            {data.selectedServer
              ? (connections[data.selectedServer]?.state ?? "DISCONNECTED")
              : "未設定"}
          </small>
        </div>
      </aside>
      <main>
        <div className="topbar">
          <span>
            Workspace{" "}
            <span className="muted">
              / {page === "chat" ? "Conversation" : page}
            </span>
          </span>
          <span className="muted">
            {active.length
              ? `${active.length} runs in progress`
              : "Ready when you are"}
          </span>
        </div>
        {error && (
          <div className="global-error" role="alert">
            {error === "NativeUnavailable"
              ? "ネイティブ接続を開始できませんでした。Tauri アプリとして起動してください。"
              : errorText(error)}
            <button
              className="quiet"
              onClick={() => setError(null)}
              aria-label="エラーを閉じる"
            >
              ×
            </button>
          </div>
        )}
        {!ready ? (
          <div className="empty">Rei Client を準備しています…</div>
        ) : (
          <>
            {page === "settings" && (
              <Settings
                userAvatar={userAvatar}
                onUserAvatar={setUserAvatar}
                servers={data.servers}
                connections={connections}
                unlocked={data.unlocked}
                notifications={data.notifications}
                pending={pending}
                onUnlock={(password) =>
                  void perform(() => call("vault_unlock", { password }))
                }
                onSave={(id, name, baseUrl, key) =>
                  void perform(async () => {
                    const serverId = await call("server_save", {
                      id,
                      name,
                      baseUrl,
                    });
                    if (key)
                      await call("credential_set", {
                        serverId,
                        credential: key,
                      });
                  })
                }
                onRemove={(serverId) =>
                  void perform(() => call("server_remove", { serverId }))
                }
                onDeleteKey={(serverId) =>
                  void perform(() => call("credential_delete", { serverId }))
                }
                onTest={(serverId) =>
                  void perform(async () => {
                    const result = await call("server_test", { serverId });
                    setConnections((c) => ({ ...c, [serverId]: result }));
                    if (result.authenticated) {
                      const list = await call("projects_list", { serverId });
                      setProjects((p) => ({ ...p, [serverId]: list }));
                    }
                  })
                }
                onNotifications={(enabled) =>
                  void perform(async () => {
                    const granted = await call("notification_settings", {
                      enabled,
                    });
                    if (!granted) setError("NotificationDenied");
                  })
                }
              />
            )}
            {page === "new" && (
              <section className="page narrow">
                <p className="eyebrow">START SOMETHING</p>
                <h1>新しい会話</h1>
                <p className="muted">
                  サーバーとプロジェクトを選択してください。
                </p>
                <form
                  className="card"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void perform(async () => {
                      if (!data.selectedServer) return;
                      const c = await call("conversation_create", {
                        serverId: data.selectedServer,
                        projectId: project,
                        title: "",
                      });
                      setSelected(c.localId);
                      setSelectedRun(undefined);
                      setPage("chat");
                    });
                  }}
                >
                  <label>
                    Server
                    <select
                      value={data.selectedServer ?? ""}
                      onChange={(e) =>
                        void perform(() =>
                          call("server_select", { serverId: e.target.value }),
                        )
                      }
                    >
                      <option value="" disabled>
                        サーバーを選択
                      </option>
                      {data.servers.map((s) => (
                        <option key={s.id} value={s.id}>
                          {s.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <h2>Projects</h2>
                  {loadingProjects ? (
                    <p>取得中…</p>
                  ) : (
                    data.selectedServer &&
                    (projects[data.selectedServer] ?? []).map((p) => (
                      <label className="project-choice" key={p.id}>
                        <input
                          type="radio"
                          name="project"
                          checked={project === p.id}
                          onChange={() => setProject(p.id)}
                        />
                        <span>
                          <strong>{p.name}</strong>
                          <small>{p.path}</small>
                        </span>
                      </label>
                    ))
                  )}
                  {!loadingProjects &&
                    !projects[data.selectedServer ?? ""]?.length && (
                      <p className="muted">
                        プロジェクトがありません。Settings
                        で接続を確認してください。
                      </p>
                    )}
                  <button
                    className="primary"
                    disabled={
                      !project || !data.unlocked || pending || loadingProjects
                    }
                  >
                    会話を開始
                  </button>
                </form>
              </section>
            )}
            {page === "conversations" && (
              <SessionList
                state={history.state}
                projects={projects[data.selectedServer ?? ""] ?? []}
                projectId={history.projectId}
                onProject={history.setProject}
                onMore={() => void history.pager.more()}
                onRefresh={() => void history.pager.refresh()}
                onRetry={() => void history.pager.retry()}
                onOpen={(id) => void openSession(id)}
                onNew={() => navigate("new")}
                enabled={!!data.selectedServer && data.unlocked}
              />
            )}
            {page === "runs" && (
              <section className="page">
                <header className="page-heading">
                  <div>
                    <p className="eyebrow">LIVE ACTIVITY</p>
                    <h1>Active Runs</h1>
                  </div>
                  <span className="pill">{active.length} active</span>
                </header>
                {!active.length ? (
                  <div className="empty">
                    <h2>実行中の Run はありません</h2>
                    <p>会話から依頼すると、ここで進行状況を確認できます。</p>
                  </div>
                ) : (
                  <div className="run-list">
                    {active.map((r) => (
                      <article
                        className="card active-run"
                        key={`${r.serverId}/${r.runId}`}
                      >
                        <button
                          className="run-link"
                          onClick={() => {
                            const c = data.conversations.find(
                              (c) => c.localId === r.conversationId,
                            );
                            if (c) openConversation(c, r.runId);
                          }}
                        >
                          <span className="status running">{r.status}</span>
                          <h2>{r.prompt}</h2>
                          <p>
                            {projectName(r.serverId, r.projectId)} ·{" "}
                            {r.streamState}
                          </p>
                        </button>
                        <button
                          className="danger"
                          onClick={() => runAction("run_cancel", r)}
                        >
                          Stop
                        </button>
                      </article>
                    ))}
                  </div>
                )}
              </section>
            )}
            {page === "chat" && selectedConversation && (
              <>
                <button
                  className="history-back"
                  onClick={() => navigate("conversations")}
                >
                  会話一覧へ戻る
                </button>
                <Chat
                  userAvatar={userAvatar}
                  key={selectedConversation.localId}
                  conversation={selectedConversation}
                  projectName={projectName(
                    selectedConversation.serverProfileId,
                    selectedConversation.projectId,
                  )}
                  runs={data.runs.filter(
                    (r) => r.conversationId === selectedConversation.localId,
                  )}
                  pending={sending.includes(selectedConversation.localId)}
                  error={chatErrors[selectedConversation.localId] ?? null}
                  selectedRun={selectedRun}
                  history={turns.state}
                  onMoreHistory={() => void turns.pager.more()}
                  onRetryHistory={() => void turns.pager.retry()}
                  onRefreshHistory={() => {
                    void turns.pager.refresh();
                    void refreshMetadata().catch(() => {});
                  }}
                  onSend={send}
                  onStop={(r) => runAction("run_cancel", r)}
                  onRefresh={(r) => runAction("run_get", r)}
                  onSubscribe={(r) => runAction("run_subscribe", r)}
                  onContinue={() =>
                    void perform(async () => {
                      const c = await call("conversation_continue_new", {
                        conversationId: selectedConversation.localId,
                      });
                      setSelected(c.localId);
                      setSelectedRun(undefined);
                    })
                  }
                />
              </>
            )}
            {page === "chat" && !selectedConversation && (
              <section className="page">
                <button onClick={() => navigate("conversations")}>
                  会話一覧へ戻る
                </button>
                {opening.state.loading && (
                  <p role="status">会話を開いています…</p>
                )}
                {opening.state.error && (
                  <div className="notice" role="alert">
                    {errorText(opening.state.error)}
                  </div>
                )}
                <button onClick={() => navigate("new")}>
                  新しい会話を開始
                </button>
              </section>
            )}
          </>
        )}
      </main>
    </div>
  );
}
