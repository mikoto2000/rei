import { isTauri } from "@tauri-apps/api/core";
import { openUrl } from "@tauri-apps/plugin-opener";
import { isValidElement, useState, type ReactNode } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";

export function CopyText({ text, label }: { text: string; label: string }) {
  const [result, setResult] = useState<string | null>(null);
  return (
    <>
      <button
        type="button"
        className="quiet"
        onClick={async () => {
          try {
            await navigator.clipboard.writeText(text);
            setResult("コピーしました");
          } catch {
            setResult(
              "コピーできませんでした。本文を選択してコピーしてください。",
            );
          }
        }}
      >
        {label}
      </button>
      {result && <small role="status">{result}</small>}
    </>
  );
}
export function Answer({
  text,
  copy = true,
}: {
  text: string;
  copy?: boolean;
}) {
  return (
    <div className="answer markdown-answer">
      <Markdown
        remarkPlugins={[remarkGfm]}
        skipHtml
        components={{
          a: ({ href, children }) => (
            <ExternalLink href={href}>{children}</ExternalLink>
          ),
          pre: ({ children }) => {
            const text = isValidElement<{ children?: ReactNode }>(children)
              ? String(children.props.children ?? "")
              : "";
            return (
              <div className="code-block">
                <CopyText text={text} label="コードをコピー" />
                <pre>{children}</pre>
              </div>
            );
          },
        }}
      >
        {text}
      </Markdown>
      {copy && <CopyText text={text} label="回答をコピー" />}
    </div>
  );
}

function ExternalLink({
  href,
  children,
}: {
  href?: string;
  children?: ReactNode;
}) {
  const [error, setError] = useState(false);
  if (!href || !/^https?:\/\//i.test(href)) return <span>{children}</span>;
  return (
    <>
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        onClick={(event) => {
          if (!isTauri()) return;
          event.preventDefault();
          void openUrl(href).catch(() => setError(true));
        }}
      >
        {children}
      </a>
      {error && (
        <small role="alert">
          リンクを開けませんでした。URLをコピーしてブラウザで開いてください。
        </small>
      )}
    </>
  );
}
