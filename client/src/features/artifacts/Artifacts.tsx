import { useEffect, useRef, useState } from "react";
import type { Command } from "../../tauri/commands";
import { errorText, type Project } from "../../entities/models";
import type {
  ArtifactPreview,
  ArtifactSelection,
  DeliveryArtifact,
} from "../../entities/artifacts";
export function Artifacts({
  call,
  serverId,
  projects,
  selection,
}: {
  call: Command;
  serverId: string | null;
  projects: Project[];
  selection?: ArtifactSelection | null;
}) {
  const [project, setProject] = useState(selection?.projectId ?? "");
  const [items, setItems] = useState<DeliveryArtifact[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [preview, setPreview] = useState<ArtifactPreview | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const generation = useRef(0);
  useEffect(() => setProject(selection?.projectId ?? ""), [selection]);
  const load = async (
    next: string | null = null,
    token = generation.current,
  ) => {
    if (!serverId) return;
    setBusy(true);
    setError(null);
    try {
      const page = selection?.artifactId
        ? {
            items: [
              await call("artifact_get", {
                serverId,
                projectId: selection.projectId,
                sessionId: selection.sessionId,
                artifactId: selection.artifactId,
              }),
            ],
            nextCursor: null,
          }
        : await call("artifacts_list", {
            serverId,
            projectId: selection?.projectId || project || null,
            sessionId: selection?.sessionId ?? null,
            runId: selection?.runId ?? null,
            limit: 50,
            cursor: next,
          });
      if (token !== generation.current) return;
      setItems((old) =>
        next
          ? [
              ...old,
              ...page.items.filter(
                (i) => !old.some((x) => x.artifactId === i.artifactId),
              ),
            ]
          : page.items,
      );
      setCursor(page.nextCursor);
    } catch (e) {
      if (token === generation.current) setError(errorText(e));
    } finally {
      if (token === generation.current) setBusy(false);
    }
  };
  useEffect(() => {
    const token = ++generation.current;
    setItems([]);
    setCursor(null);
    setPreview(null);
    setSaved(null);
    setBusy(false);
    setError(null);
    void load(null, token);
    return () => {
      generation.current++;
    };
  }, [serverId, project, selection, call]);
  const action = async (item: DeliveryArtifact, save: boolean) => {
    if (!serverId || busy) return;
    const token = generation.current;
    setBusy(true);
    setError(null);
    setSaved(null);
    if (!save) setPreview(null);
    const args = {
      serverId,
      projectId: item.projectId,
      sessionId: item.sessionId,
      artifactId: item.artifactId,
    };
    try {
      if (save) {
        const result = await call("artifact_save", args);
        if (token === generation.current) setSaved(result.path);
      } else {
        const result = await call("artifact_preview", args);
        if (token === generation.current) setPreview(result);
      }
    } catch (e) {
      if (token === generation.current) setError(errorText(e));
    } finally {
      if (token === generation.current) setBusy(false);
    }
  };
  return (
    <section className="page narrow artifact-manager">
      <h2>Artifact</h2>
      <p>サーバーに保存された生成物を確認・保存します。</p>
      {!serverId ? (
        <p>サーバーを選択し、認証を解除してください。</p>
      ) : (
        <>
          <label>
            ArtifactのProject
            <select
              value={project}
              disabled={busy || !!selection}
              onChange={(e) => setProject(e.target.value)}
            >
              <option value="">すべて</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          {selection?.runId && <p>Run: {selection.runId}</p>}
          <button disabled={busy} onClick={() => void load()}>
            更新
          </button>
          {error && <p role="alert">{error}</p>}
          {saved && <p role="status">保存先: {saved}</p>}
          {items.map((item) => (
            <article className="card" key={item.artifactId}>
              <h3>{item.filename}</h3>
              <p>{item.status}</p>
              <p>
                {item.mediaType} · {item.size} bytes
              </p>
              <p>SHA-256: {item.sha256}</p>
              <p>作成: {item.createdAt}</p>
              {item.expiresAt && <p>期限: {item.expiresAt}</p>}
              <button
                disabled={
                  busy ||
                  item.status !== "AVAILABLE" ||
                  item.size > 2 * 1024 * 1024 ||
                  ![
                    "text/plain",
                    "text/markdown",
                    "application/json",
                    "image/png",
                    "image/jpeg",
                  ].includes(item.mediaType)
                }
                onClick={() => void action(item, false)}
              >
                preview
              </button>
              <button
                disabled={busy || item.status !== "AVAILABLE"}
                onClick={() => void action(item, true)}
              >
                Downloadsへ保存
              </button>
            </article>
          ))}
          {!busy && !items.length && <p>Artifactはありません。</p>}
          {cursor && (
            <button disabled={busy} onClick={() => void load(cursor)}>
              さらに読み込む
            </button>
          )}
          {preview && (
            <article className="card">
              <h3>{preview.artifact.filename} のpreview</h3>
              {preview.text !== null && <pre>{preview.text}</pre>}
              {preview.dataUrl && (
                <img
                  src={preview.dataUrl}
                  alt={preview.artifact.filename}
                  onError={() => {
                    setPreview(null);
                    setError("画像を表示できませんでした");
                  }}
                />
              )}
            </article>
          )}
        </>
      )}
    </section>
  );
}
