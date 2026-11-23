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
        onClick={() => {
          void navigator.clipboard.writeText(text).then(
            () => setResult("コピーしました"),
            () =>
              setResult(
                "コピーできませんでした。本文を選択してコピーしてください。",
              ),
          );
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
            <a href={href} target="_blank" rel="noopener noreferrer">
              {children}
            </a>
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
