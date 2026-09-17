# Session History API と Shell

Session は Shell の新規入力または `POST /api/v1/chat` の受理時に永続化されます。再起動後も一覧・詳細・Turn を参照でき、取得した `sessionId` をそのまま Chat に渡して会話を継続できます。`sessionId = conversationId`、`turnId = runId` を維持します。sessionId は UUID 単体とは限らず、現在は `project:<project UUID>:chat:<UUID>` です。クライアントは値を解析せず、そのまま使用してください。

## HTTP

すべて `Authorization: Bearer <token>` が必要です。未指定・不正キーは401です。

| GET | 内容 | 順序 |
| --- | --- | --- |
| `/api/v1/sessions?projectId=...&limit=50&cursor=...` | Session 一覧。projectId を省略すると全 project。未知 projectId は空一覧 | updatedAt DESC、sessionId ASC |
| `/api/v1/sessions/{sessionId}` | Session 詳細。未知 ID は404 | — |
| `/api/v1/sessions/{sessionId}/turns?limit=50&cursor=...` | 永続 Turn 一覧。未知 Session は404、未実行なら空一覧 | createdAt ASC、runId ASC |

両一覧の limit は既定50、1〜100です。0・負数・101以上・整数以外、不正 cursor は400です。cursor はレスポンスの `nextCursor` をそのまま渡し、最終ページの `nextCursor` は null です。同じ projectId 条件／同じ Session で使用してください。別条件や別 API への流用は400です。

Session 一覧は `{ "items": [...], "nextCursor": null }`、詳細は次の object です。

```json
{
  "sessionId": "project:550e8400-e29b-41d4-a716-446655440000:chat:550e8400-e29b-41d4-a716-446655440010",
  "projectId": "550e8400-e29b-41d4-a716-446655440000",
  "title": "このコードを調べて",
  "createdAt": "2026-09-16T08:00:00Z",
  "updatedAt": "2026-09-16T08:05:00Z"
}
```

title は最初の user message の先頭80 Unicode code point です。日本語・絵文字の surrogate pair を分断せず、省略記号を追加しません。結合文字列を一つにまとめる grapheme 単位ではありません。継続時も title・projectId・createdAt は変えず、受理時の Clock で updatedAt を更新します。並行 submit や時計の巻き戻りで updatedAt が後退することはありません。

Turn 一覧:

```json
{
  "sessionId": "project:550e8400-e29b-41d4-a716-446655440000:chat:550e8400-e29b-41d4-a716-446655440010",
  "items": [{
    "turnId": "550e8400-e29b-41d4-a716-446655440020",
    "runId": "550e8400-e29b-41d4-a716-446655440020",
    "userMessage": "このコードを調べて",
    "assistantMessage": "確認しました。",
    "createdAt": "2026-09-16T08:00:01Z"
  }],
  "nextCursor": null
}
```

Turn の createdAt は runner 開始時刻です。同一会話内の同時刻・時計の巻き戻りでは直前の保存時刻 + 1ns とし、実行順を保ちます。QUEUED の間はまだ Turn がありません。応答未記録の Turn は `assistantMessage: null` です。SSE の中間 delta、tool message、追加入力の全ログはこの Turn DTO には含めません。Session の削除・改名 API は追加していません。未知 Session の Chat は404、所属 project の不一致は409です。

## Shell

```text
/history
/history --limit 20
/history --project-id <projectId> --limit 20 --cursor <nextCursor>
/history show <sessionId>
/history show <sessionId> --limit 20 --cursor <nextCursor>
```

`/history` は全 Session の最初のページを表示します。full sessionId、更新日時、project 名と ID、title を確認できます。`show <sessionId>` は metadata と時系列 Turn を表示します。いずれも既定50／最大100件で、次ページのコマンドを表示します。表示本文は既存 formatter に従い認証情報の伏せ字・制御文字除去・長文の表示上限を適用します。

旧履歴コマンドは互換性のため残します。

```text
/history show
/history show --last 100
/history show --all
/history show chat:test --project "MaCa Editor" --last 100
/history list --project "MaCa Editor" --limit 50 --offset 50
/history search --current "検索語"
```

`show` 単体は選択中 project の直近50メッセージです。従来の `/history` 単体の表示はこちらで利用できます。明示的な旧 conversation ID に metadata がない場合は既存ログの参照に戻ります。`--project`／`--last`／`--all` は旧ログ参照用で、新しい `--limit`／`--cursor` と併用できません。旧 `history list` の offset は互換機能であり、新 Session 一覧は Web と同じ cursor query を使います。

## 永続化・移行・制約

- `SessionRepository` の実装は `FileSessionRepository`。保存先は `<rei-data-dir>/sessions.json`。既存 ProjectRegistry と同様に一時ファイルから atomic replace し、非対応ファイルシステムでは replace にフォールバックします。
- create/touch/read/list は同一 Repository の monitor で保護します。metadata を保存してから runtime cache・QUEUED run・queue を登録します。同期的な enqueue 失敗は metadata を復元し、登録した runtime run/cache を除去します。保存失敗時は enqueue しません。復元自体の I/O 失敗は元例外の suppressed exception に残します。
- SessionRegistry は30分の runtime cache です。purge しても永続 metadata は削除せず、Chat の存在・所属判定は Repository が行います。RunRegistry／ReplayBuffer の保持期間は変更していません。
- 共通 `SessionQueryService` は `SessionRepository` と `ConversationHistory` port を読みます。後者は既存 `ConversationTurnStore` が実装し、同じ永続 Turn に時刻と最終応答を追加しています。Web は専用 DTO、Shell は既存 formatter を使用します。
- カーソルは version・resource/filter scope・時刻（秒＋nano）・ID の URL-safe Base64 です。storage path は含めません。暗号化・署名付き token ではありません。認証は既存 Bearer filter が担います。
- 一覧は keyset pagination です。新しい Session が先頭に挿入されても既存ページが offset のようにずれません。ただし snapshot pagination ではなく、未取得 Session の updatedAt が途中で更新されカーソルより前へ移動した場合は今回の走査から外れます。先頭から再取得すると見つかります。
- backfill は実施しません。旧 Turn ファイルは conversationId がファイル名に残らず、時刻・応答がありません。旧 JSONL は runId がなく、介入メッセージや tool 出力から Turn の対応と Chat 受理時刻を確実に再構成できません。旧データは変更せず、従来の show/list/search で参照できます。新 Session 一覧は共通 SessionLifecycle が受理した Shell / Web Session が対象です。導入前の固定 `chat:main` を継続・登録することはなく、旧ログの走査や ID・title・時刻の推測も行いません。
- 旧 Turn の欠落フィールドは null として読み込めます。日時のない旧レコードは新 Turn pagination から除外し、既存 lifecycle／cancelledContext の参照には残します。
- ファイル adapter は単一アプリケーション writer を前提とします。複数プロセスから同じ data-dir に同時書込みする用途には対応していません。JSON 読込／ソートは全 metadata、Turn 保存・読込は会話全体を対象とし、返却のみページ単位です。大規模データには port の DB 実装への差替えが必要です。
- queue は既存の in-memory queue です。プロセス停止時に未実行の仕事は再実行しません。保存直後のクラッシュでは空 Turn の Session が残ることがありますが、その ID で新しい Chat を受理できます。

## Shell の Session lifecycle

Shell は最初の送信時に `project:<project UUID>:chat:<UUID>` を新規作成します。作成前に台帳へ空 Session を追加しません。Shell 起動ごとに未選択で始まり、最後の Session を自動再開しません。

- `/session new`: 選択を解除し、次の入力で新 Session を作成。実行中の Run は中止しません。
- `/session resume <sessionId>`: 現在の project に属する既知 Session を選択。旧 `chat:main` や未知 ID は拒否します。別 project の場合は先に `/project cd` でその project を選びます。
- `/project cd`: project が変わった場合は Session 選択を解除。元の Session の projectId は変更しません。同じ project の再選択では Session を保持します。
- `/history` / `/history show <sessionId>`: Shell / Web 共通の台帳・Turn を参照します。

`ChatCommand` と `/agent` は ShellConversationService に委譲し、Web ChatSubmitService と共通の SessionLifecycle を利用します。初回入力の先頭80 Unicode code points を既存 SessionTitle で採用し、継続時は title / createdAt / projectId を保って updatedAt のみ更新します。保存または touch が失敗すれば Run を enqueue せずエラーにします。同期 enqueue 失敗は metadata を元に戻し、Shell の選択も変えません。

Shell の実行中追加入力は同一 Run への介入ではなく、同一 Session の次の Run として project FIFO に入ります。`1 Run = 1 Turn` です。SessionLifecycle は Repository の monitor 内で検証・保存・enqueue を行い、Shell / Web の競合でも受付順と queue 順を一致させます。実行は monitor 外で行います。実行開始時に共通 ChatExecutionService → ConversationTurnStore へ保存し、ConversationLogStore と ChatMemory も同じ conversationId を使います。ChatMemory は既存のメモリーウィンドウであり、今回再起動時のモデル文脈復元は追加していません。

Native Client は同じ Rei プロセスの Web API に接続して一覧を更新すると、Shell の新しい Session を表示・再開できます。HTTP schema と Native Client の変更は不要です。Web で開始した Session も `/session resume` で Shell から選択できます。同じ data-dir に Shell プロセスと Web 専用プロセスを並行して書き込む構成は引き続き対象外です。
