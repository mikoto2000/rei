import { useState } from "react";
import type { Server, Connection } from "../../entities/models";
interface Props {
  servers: Server[];
  connections: Record<string, Connection>;
  unlocked: boolean;
  notifications: boolean;
  pending: boolean;
  onUnlock: (password: string) => void;
  onSave: (id: string | null, name: string, url: string, key: string) => void;
  onRemove: (id: string) => void;
  onDeleteKey: (id: string) => void;
  onTest: (id: string) => void;
  onNotifications: (enabled: boolean) => void;
}
export function Settings(props: Props) {
  const [editing, setEditing] = useState<string | null>(null);
  const server = props.servers.find((s) => s.id === editing);
  return (
    <section className="page">
      <header className="page-heading">
        <div>
          <p className="eyebrow">PREFERENCES</p>
          <h1>Settings</h1>
        </div>
        <span className="pill">Rei Client · 0.1</span>
      </header>
      {!props.unlocked && (
        <section className="card">
          <h2>Credential Vault</h2>
          <p>
            起動ごとに解錠します。初回は12文字以上のパスフレーズを設定してください。
          </p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const form = e.currentTarget;
              const input = form.elements.namedItem(
                "password",
              ) as HTMLInputElement;
              const password = input.value;
              input.value = "";
              props.onUnlock(password);
            }}
          >
            <label>
              Vault パスフレーズ
              <input
                name="password"
                type="password"
                autoComplete="off"
                minLength={12}
                required
              />
            </label>
            <button className="primary" disabled={props.pending}>
              解錠 / 作成
            </button>
          </form>
        </section>
      )}
      <div className="settings-grid">
        <section className="card">
          <h2>Rei Servers</h2>
          <p>LAN / VPN 上のサーバーへ接続します。HTTPS を推奨します。</p>
          {props.servers.map((s) => (
            <div className="server-card" key={s.id}>
              <div>
                <strong>{s.name}</strong>
                <p className="file-path">{s.baseUrl}</p>
                <small>
                  {props.unlocked
                    ? s.hasCredential
                      ? "Credential 保存済み"
                      : "Credential 未設定"
                    : "Vault 未解錠"}
                </small>
              </div>
              <div className="button-row">
                <button onClick={() => setEditing(s.id)}>編集</button>
                <button
                  disabled={props.pending}
                  onClick={() => props.onTest(s.id)}
                >
                  Test Connection
                </button>
                <button
                  className="quiet danger"
                  disabled={props.pending}
                  onClick={() => props.onRemove(s.id)}
                >
                  削除
                </button>
              </div>
              {props.connections[s.id] && (
                <div className="connection-result">
                  <span>
                    Server reachable:{" "}
                    {props.connections[s.id].reachable ? "Yes" : "No"}
                  </span>
                  <span>
                    Authentication OK:{" "}
                    {props.connections[s.id].authenticated ? "Yes" : "No"}
                  </span>
                  <small>{props.connections[s.id].state}</small>
                </div>
              )}
            </div>
          ))}
          {!props.servers.length && (
            <p className="muted">まだサーバーがありません。</p>
          )}
        </section>
        <section className="card">
          <h2>{server ? "サーバーを編集" : "サーバーを追加"}</h2>
          <form
            key={server?.id ?? "new"}
            onSubmit={(e) => {
              e.preventDefault();
              const form = e.currentTarget;
              const data = new FormData(form);
              const key = String(data.get("key") ?? "");
              (form.elements.namedItem("key") as HTMLInputElement).value = "";
              props.onSave(
                server?.id ?? null,
                String(data.get("name")),
                String(data.get("url")),
                key,
              );
            }}
          >
            <label>
              Name
              <input
                name="name"
                defaultValue={server?.name ?? ""}
                placeholder="Home Rei"
                required
              />
            </label>
            <label>
              URL
              <input
                name="url"
                type="url"
                defaultValue={server?.baseUrl ?? ""}
                placeholder="https://rei.example"
                required
              />
            </label>
            <label>
              API Key
              <input
                name="key"
                type="password"
                autoComplete="off"
                disabled={!props.unlocked}
                placeholder={
                  server?.hasCredential ? "変更する場合のみ入力" : "API Key"
                }
              />
            </label>
            <small>入力後は Rust の暗号化 Vault に保存します。</small>
            <div className="button-row">
              <button className="primary" disabled={props.pending}>
                Save
              </button>
              {server && (
                <>
                  <button type="button" onClick={() => setEditing(null)}>
                    追加に戻る
                  </button>
                  <button
                    type="button"
                    disabled={!props.unlocked || props.pending}
                    onClick={() => props.onDeleteKey(server.id)}
                  >
                    Key を削除
                  </button>
                </>
              )}
            </div>
          </form>
        </section>
      </div>
      <section className="card">
        <h2>Notifications</h2>
        <label className="checkbox">
          <input
            type="checkbox"
            checked={props.notifications}
            disabled={props.pending}
            onChange={(e) => props.onNotifications(e.target.checked)}
          />
          Run の完了・失敗を通知する
        </label>
        <p className="muted">OS の通知権限がなくても、Run は実行を続けます。</p>
      </section>
      <section className="card about">
        <h2>About Rei Client</h2>
        <p>Run と Conversation のためのネイティブクライアント。</p>
        <small>React → Tauri / Rust → Rei Server · Phase 1–2</small>
      </section>
    </section>
  );
}
