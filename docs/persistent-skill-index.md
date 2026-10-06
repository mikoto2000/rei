# Semantic Skillメタデータの永続索引

```yaml
rei:
  skills:
    semantic:
      enabled: true
      persistent-index-enabled: true
      index-namespace: local-provider-model-v1
```

既定は無効。`REI_SKILLS_SEMANTIC_PERSISTENT_INDEX_ENABLED`と`REI_SKILLS_SEMANTIC_INDEX_NAMESPACE`でも設定できる。有効化には1〜128文字の明示namespaceが必要で、英数字・`.`・`_`・`:`・`-`を許容する。namespaceはembedding provider・モデル・revision・前処理の組を識別する管理設定である。モデルや設定を変更したらnamespaceも変えて再起動する。同じ次元の別モデルへの変更を自動検出できるとは扱わない。

既存application SQLite DBに、最大256件のメタデータvector snapshotを保存する。追加DB・sqlite-vec拡張は使わない。index bean作成時や既定の無効設定ではSQLを実行せず、明示有効化した検索で初めてtableを作成する。

保存対象はformat固定のname/description/keywords profileのSHA-256、namespace、dimension、正規化float vector、vector checksumだけ。Skill本文・path・Skillオブジェクト・profile原文・問い合わせ・問い合わせvectorを保存しない。vector自体はmetadataに由来するデータとして既存DBの管理対象になる。checksumは破損検出であり、改ざんに対する署名ではない。

検索時は現在カタログにある有効Skillのprofileに一致するvectorだけを読み、現在のSkillオブジェクトを返す。説明／keywords変更はprofile hashが変わり再生成する。本文のみ変更ならメタデータvectorを再利用する。成功した有効カタログのsnapshotで削除／無効Skillを掃除し、空カタログではembeddingせずsnapshotを空にする。過大catalog・長い問い合わせ等で処理を省略した場合は、既存snapshotを完成した新catalogと見なして置き換えない。

再起動した温索引ではqueryの1 embeddingバッチだけで検索できる。欠損や破損行はmetadataを再embeddingする。queryは常に現在providerでembeddingし、既存のRRF・keyword・reranker経路を再利用する。独自の旧constructorは従来のメモリcacheのみを使う。namespaceを変えると再embeddingし、成功更新時に旧namespaceのsnapshotを置き換える。1 DBに1つのactive snapshotであり、namespaceごとに無制限に蓄積しない。

更新は全入力の検証後にDELETEとINSERTを同一transactionで行い、失敗・取消ではrollbackする。各vectorは最大8192次元・有限値・単位normまたはzero、profileは2048文字、snapshotは256件。読取は257行目で過大snapshotを拒否し、blobの取得も最大32769bytesに制限する。SQL timeoutは5秒、検索／更新途中の取消は既存Runへ伝播する。

DBの読取／保存障害はlive embeddingや既存候補を維持し、ログには例外クラスのみを記録する。provider障害時はkeywordと既存backoffへ戻り、保存済みの正常metadataを削除しない。次元不一致では誤ったvectorを使わずsnapshotをinvalidateする。保存が更新されるまでは再利用を避け、backoff後にlive metadataで検索を回復する。

永続索引は権限付与・Skill実行・品質保証を行わない。複数processで同じDBのcatalogを更新する場合、最後に保存したsnapshotが残り、別catalogでは再生成が必要となり得る。学習済み検索・実データの品質評価、embedding/rerankの共有費用予算は引き続き別対応。
