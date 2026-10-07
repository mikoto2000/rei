# Text Change Set / Diff / Apply

既存の自然言語Chatが生成した編集案を、ファイル書き込みから分けて確認する共通基盤。追加LLMやExternal Agentを起動せず、既存のModel/Tool loop・Policy・編集Eventを再利用する。自己修正や外部レビュー後の修正提案にも利用できるが、それらの自動ループ自体は別機能である。

## 操作

1. `readTextChangeSetBase(path)` で選択Project内の既存UTF-8ファイルを読み、正確なtext/path/SHA-256を取得する。BOM/CRLF/末尾改行も保持するため、行表示の改行情報を推測する必要がない。
2. `proposeTextChangeSet({path,expectedText,replacement})` へ読んだ完全なtextと置換内容を渡す。現在ファイルとの完全一致を確認し、提案をSQLite保存する。対象ファイルは変更しない。
3. 返された差分・元/提案hash・提案IDと`proposalSha256`を確認する。`inspectTextChangeSet(id)` は保存案と現在hashを再確認する読み取り操作。
4. `applyTextChangeSet(id,proposalSha256)` はその保存提案だけを一回適用する。現在の元hashが変わっていればSTALEとして拒否する。適用内容を再読取し、提案hashと一致した後だけAPPLIEDを保存する。
5. 未適用の提案を採用しない場合は`discardTextChangeSet(id,proposalSha256)`で破棄する。ファイルは変更しない。

read/inspectはREAD。proposeは提案DB保存、apply/discardはLOCAL_WRITEであり、既存Policyの承認・拒否に従う。提案hashは内容の同一性確認で、人間の承認を偽装するものではない。既存RunのProject所有者を優先し、Shell外の新しい経路を作らない。SubAgentへの許可拡張は行わない。

## 保存・再実行・制限

PROPOSEDからDBの原子的比較でAPPLYINGを取得する。別サービス/プロセスの同一提案のclaimは拒否する。APPLIEDの再呼出しは保存receiptの読取になり、writerを実行しない。`currentSha256`はその読取時点の内容なので、APPLIED後に他者が変更した場合はproposedSha256と異なる。

適用失敗・キャンセルはFAILED_UNCERTAINへ移す。再起動時に残ったAPPLYINGも自動再実行しない。現在の内容を確認し、必要なら新しいbaselineで別提案を作る。元ファイルが消えた、binary等に変わった場合もinspectは保存状態/差分を返し、currentSha256=nullを「確認できない」として示す。空ファイルや適用成功に置き換えない。

対象は単一の既存・非空のUTF-8 regular file。元/新内容は各64KiB以内。全内容置換の読み取り用diffを返し、実行可能patchとして解釈しない。新規ファイル・削除・複数ファイルtransaction・binary/CP932・diagram構文の正しさは対象外。空のreplacementによる内容の削除は提案可能である。

Project/root境界、相対path、symlink、秘密/生成directory・.env/鍵等の除外は既存Repository Map基準を再利用する。root変更は同じProject IDでも拒否する。Projectあたり128提案まで。古いAPPLIED/STALE/DISCARDEDは直近100件を残して整理し、未claim/不確定状態は黙って消さない。128件の非終端状態で上限に達した場合は既存案を確認・破棄する。

Applyは既存編集のWorking Set/Recent Changes・各cache無効化・FileModified Eventを共有する。書込直前に元内容とpathを再確認し、NOFOLLOW_LINKS・TRUNCATE_EXISTINGで既存ファイルを置換する。SQLiteとfilesystemの分散transactionや、別プロセスの書込に対するOSレベルの原子的compare-and-swapではない。途中失敗や再起動を成功として扱わず、結果不明のまま保持する。

保存する提案は元/新のファイル本文を含むため、通常のProject作業データとして既存DBに保持する。コマンド・network・勝手なcommit/Push・自動再試行は実行しない。
# Multi-file extension

UPDATE/CREATE/DELETE/RENAME proposals now share this repository and permission
boundary. See [multi-file document changes](multi-file-document-change-set.md) for
whole-set hashes, durable staging journals, explicit recovery and ownership limits.
