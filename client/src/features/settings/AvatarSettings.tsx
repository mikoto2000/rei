import { useState } from "react";
import { Avatar } from "../../shared/Avatar";
import { readUserAvatar } from "./userAvatar";

export function AvatarSettings({
  avatar,
  onChange,
}: {
  avatar: string | null;
  onChange: (value: string | null) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <section className="card">
      <h2>ユーザーアイコン</h2>
      <p>会話に表示するあなたのアイコンです。この端末に保存されます。</p>
      <div className="avatar-settings">
        <Avatar role="user" src={avatar} />
        <label>
          アイコン画像を選択
          <input
            type="file"
            accept="image/png,image/jpeg,image/webp"
            disabled={busy}
            onChange={async (event) => {
              const file = event.currentTarget.files?.[0];
              event.currentTarget.value = "";
              if (!file) return;
              setBusy(true);
              setError(null);
              try {
                onChange(await readUserAvatar(file));
              } catch (e) {
                setError(
                  e instanceof Error
                    ? e.message
                    : "画像を保存できませんでした。",
                );
              } finally {
                setBusy(false);
              }
            }}
          />
        </label>
        <button
          disabled={busy || !avatar}
          onClick={() => {
            setError(null);
            try {
              onChange(null);
            } catch (e) {
              setError(
                e instanceof Error
                  ? e.message
                  : "アイコンをリセットできませんでした。",
              );
            }
          }}
        >
          標準に戻す
        </button>
      </div>
      <small>PNG / JPEG / WebP · 2 MB 以下</small>
      {busy && <p role="status">画像を読み込んでいます…</p>}
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
