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
    setSelected(conversation.localId);
    setSelectedRun(runId);
    setPage("chat");
    void perform(() =>
      call("conversation_select", { conversationId: conversation.localId }),
    );
  };
  const projectName = (server: string, id: string) =>
    projects[server]?.find((p) => p.id === id)?.name ?? id;
  const selectedConversation = data.conversations.find(
    (c) => c.localId === selected,
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
            setPage("conversations");
          }}
        >
          <span className="brand-mark">r.</span>
          <div>
            rei<span>WORKSPACE CLIENT</span>
          </div>
        </a>
        <button
          className="new-chat"
          aria-label="新しい会話"
          onClick={() => {
            setError(null);
            setPage("new");
          }}
        >
          ＋ 新しい会話
        </button>
        <nav aria-label="メイン">
          <button
            className={
              page === "conversations" || page === "chat" ? "selected" : ""
            }
            onClick={() => setPage("conversations")}
          >
            ▤ Conversations <span>{data.conversations.length}</span>
          </button>
          <button
            className={page === "runs" ? "selected" : ""}
            onClick={() => setPage("runs")}
          >
            ◉ Active Runs <span>{active.length}</span>
          </button>
          <button
            className={page === "settings" ? "selected" : ""}
            onClick={() => setPage("settings")}
          >
            ⚙ Settings
          </button>
        </nav>
        <div className="sidebar-recent">
          <p className="eyebrow">RECENT CONVERSATIONS</p>
          {data.conversations.slice(0, 7).map((c) => (
            <button
              key={c.localId}
              onClick={() => openConversation(c)}
              className={
                selected === c.localId && page === "chat" ? "selected" : ""
              }
            >
              {c.title}
              <small>{projectName(c.serverProfileId, c.projectId)}</small>
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
              <section className="page">
                <header className="page-heading">
                  <div>
                    <p className="eyebrow">YOUR WORKSPACE</p>
                    <h1>Conversations</h1>
                    <p className="muted">れいと進める、プロジェクトの続き。</p>
                  </div>
                  <span className="pill">
                    {data.conversations.length} conversations
                  </span>
                  <button className="primary" onClick={() => setPage("new")}>
                    会話を追加
                  </button>
                </header>
                {!data.conversations.length ? (
                  <div className="empty">
                    <div className="rei-mark">r.</div>
                    <h2>会話を始めましょう</h2>
                    <p>プロジェクトを選んで、れいに作業を依頼します。</p>
                    <button className="primary" onClick={() => setPage("new")}>
                      プロジェクトを選ぶ
                    </button>
                  </div>
                ) : (
                  <div className="conversation-grid">
                    {data.conversations.map((c) => (
                      <article className="conversation-card" key={c.localId}>
                        <button onClick={() => openConversation(c)}>
                          <span className="eyebrow">
                            {projectName(c.serverProfileId, c.projectId)}{" "}
                            {c.sessionId ? "🔒" : ""}
                          </span>
                          <h2>{c.title}</h2>
                          <p>
                            {
                              data.servers.find(
                                (s) => s.id === c.serverProfileId,
                              )?.name
                            }
                          </p>
                          <small>
                            {new Date(c.lastAccessedAt).toLocaleString()}
                          </small>
                        </button>
                        <button
                          className="quiet delete"
                          disabled={pending}
                          aria-label={`${c.title} を削除`}
                          onClick={() =>
                            void perform(() =>
                              call("conversation_delete", {
                                conversationId: c.localId,
                              }),
                            )
                          }
                        >
                          削除
                        </button>
                      </article>
                    ))}
                  </div>
                )}
              </section>
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
              <Chat
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
            )}
          </>
        )}
      </main>
    </div>
  );
}
