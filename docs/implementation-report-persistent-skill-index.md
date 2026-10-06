# Semantic Skill永続索引 実装記録

## 変更

監査B12の永続index不足を、既存Semantic Skill Searchの明示opt-in cacheとして実装した。既存application SQLite DBへ最大256件のmetadata profile SHA・明示model/version namespace・dimension・normalized vector・checksumをtransaction snapshot保存する。raw metadata・path・Skill本文・query・query vectorは保存しない。新DB・sqlite-vec・追加LLMは使わない。

現在の有効catalogだけを読み、再起動後もqueryのembeddingだけで検索できる。metadata変更は再生成し、削除・無効化・空catalogは掃除する。現在Skillオブジェクトを返し、既存keyword/RRF/rerank・provider backoff・取消を維持する。DB障害はlive検索を維持し、破損行はcache miss、次元変更は古いsnapshotの再利用停止とinvalidate後のlive回復を行う。同次元モデル変更は自動検出せず、明示namespace変更を必要とする。

## 検証

初期Redは永続index・設定・constructor未実装によるcompile失敗。再起動後のmetadata再利用／queryだけの追加呼出、現在Skill返却・説明変更とsnapshot更新で最小Greenを確認した。

全Skill削除時の旧索引残存を振る舞いRed（1 failure / 0 error）で検出し、cold empty catalogでも掃除するよう修正した。さらに古い次元の索引をinvalidateできないread-only DB相当portで、backoff後も検索が回復しない振る舞いRed（1 failure / 0 error）を検出し、永続snapshot更新まで再読込を避けるよう修正した。

追加テストでnamespace変更・次元変更後の再生成、checksum破損のmiss／修復、finite/norm/size/profile/256件制限、hash保存、実SQLite triggerによるINSERT失敗と全更新rollback、削除profile掃除、無効設定でSQLなし、DB read/write障害時live結果保持、provider一時障害時の正常snapshot保持、index read/write取消・interrupt保持、Spring Binderとlazy bean、生成／同梱設定を検証した。

最終関連テスト（PersistentSkillIndexTest・SemanticSkillSearchTest・SkillEmbeddingClientTest・SkillCandidateSelectorTest・ExternalConfigFileServiceTest）はPASS。全体回帰は3140 tests / 596 suites、failure/error/skip各0。Java/configのみ変更し、Native/Reactは再実行していない。

feature ccfa7adfをPushし、main 4a7c2740へMergeした。Merge後の同じ関連テストもPASS、main Push済み。設定と限界は[persistent-skill-index.md](persistent-skill-index.md)を参照。学習／実データ品質評価・embedding/rerank共有費用予算等は残件。
