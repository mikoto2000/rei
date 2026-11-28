import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import { errorText } from "../../entities/models";
import type { WorkspaceResult } from "../../entities/workspace";

type Item = WorkspaceResult["items"][number];
const field = (item: Item, name: string) =>
  item.fields.find(([key]) => key === name)?.[1] ?? "";

export function AutonomousNotices({
  call,
  serverId,
  onOpenDraft,
}: {
  call: Command;
  serverId: string | null;
  onOpenDraft: (serverId: string, draftId: string) => void;
}) {
  const [snapshot, setSnapshot] = useState<{
    owner: string;
    items: Item[];
  } | null>(null);
  const [error, setError] = useState<{ owner: string; message: string } | null>(
    null,
  );
  const [pending, setPending] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const generation = useRef(0);
  const busy = useRef(false);
  useEffect(() => {
    const epoch = ++generation.current;
    setSnapshot(null);
    setError(null);
    setPending(Boolean(serverId));
    busy.current = Boolean(serverId);
    if (serverId) {
      void call("workspace_execute", {
        serverId,
        operation: { operation: "autonomousNotifications", limit: 100 },
      })
        .then((result) => {
          if (epoch === generation.current)
            setSnapshot({ owner: serverId, items: result.items });
        })
        .catch((failure) => {
          if (epoch === generation.current)
            setError({ owner: serverId, message: errorText(failure) });
        })
        .finally(() => {
          if (epoch === generation.current) {
            busy.current = false;
            setPending(false);
          }
        });
    }
    return () => {
      generation.current++;
    };
  }, [call, serverId, refresh]);
  const visible = snapshot?.owner === serverId ? snapshot : null;
  const acknowledge = async (id: string) => {
    if (!serverId || !visible || busy.current) return;
    busy.current = true;
    setPending(true);
    setError(null);
    const epoch = generation.current;
    try {
      await call("workspace_execute", {
        serverId,
        operation: { operation: "autonomousNoticeAck", id },
      });
      if (epoch === generation.current)
        setSnapshot((current) =>
          current?.owner === serverId
            ? {
                ...current,
                items: current.items.filter((item) => item.id !== id),
              }
            : current,
        );
    } catch (failure) {
      if (epoch === generation.current)
        setError({ owner: serverId, message: errorText(failure) });
    } finally {
      if (epoch === generation.current) {
        busy.current = false;
        setPending(false);
      }
    }
  };
  return (
    <section aria-label="投稿案の通知">
      <h2>投稿案の通知</h2>
      <p>
        通知の既読操作では公開しません。本文と投稿先は詳細画面で確認してください。
      </p>
      <button
        disabled={!serverId || pending}
        onClick={() => setRefresh((value) => value + 1)}
      >
        投稿案の通知を更新
      </button>
      {pending && <p role="status">通知を取得しています…</p>}
      {error?.owner === serverId && <p role="alert">{error.message}</p>}
      {visible?.items.length === 0 && <p>確認待ちの投稿案通知はありません。</p>}
      {visible?.items.map((item) => (
        <article className="card" key={item.id}>
          <h3>{item.title}</h3>
          <p>
            投稿案: {field(item, "DraftId")} / {field(item, "CreatedAt")}
          </p>
          <button
            disabled={pending}
            onClick={() => {
              if (serverId) onOpenDraft(serverId, field(item, "DraftId"));
            }}
          >
            投稿案の詳細を開く
          </button>
          <button
            disabled={pending || !item.id}
            onClick={() => void acknowledge(item.id!)}
          >
            投稿案の通知を既読にする
          </button>
        </article>
      ))}
    </section>
  );
}
