export function DiscardConfirmation({
  task,
  request,
  pending,
  onDiscard,
  onBack,
}: {
  task: string;
  request: string;
  pending: boolean;
  onDiscard: () => void;
  onBack: () => void;
}) {
  return (
    <aside aria-label="チェックポイントの破棄確認">
      <p>対象タスク {task}</p>
      <p>{request}</p>
      <p>このタスクをチェックポイントから再開できなくなります</p>
      <p>保存履歴は残ります。</p>
      <button className="danger" disabled={pending} onClick={onDiscard}>
        チェックポイントを破棄
      </button>
      <button disabled={pending} onClick={onBack}>
        戻る
      </button>
    </aside>
  );
}
