# Repository Map

CHATの`repositoryMap`でGit管理下とignore対象外の未追跡ファイルを構造検索する。
queryはpath/package/module/symbolの部分一致、limitは1〜100（既定20）。
JavaはJDK compilerのparse-only ASTから型・method・import・public static void main(String[])を取得する。
ソース本文を結果やLLMに送らず、annotation processor・compile・実行は行わない。他言語は一覧のみ。

IMPORTは索引内の一意な型への明示import。完全な意味解析には対応しない。
TEST_NAME_CANDIDATEはtest配下の同一packageのFooTest/Foo命名候補で、実coverageではない。
moduleは /src/ より前のpath候補。Maven/Gradle依存解決やframework entrypointは未対応。

Git inventoryは5秒・1MiB上限、全体はファイル間で10秒予算を確認する。
一覧1024件、Java各128KiB、合計読取16MiB、各64symbol/128importに制限する。
resultは最大100file/200relation。queryと結果上限で省略される項目もある。
partial/warnings/各fileのstatusを確認する。relation上限は200件で切り詰める。
Git inventory失敗時はエラーを返し、無制限walkへfallbackしない。
root外・symlink file・既知の秘密設定名・build/cacheディレクトリを除外する。

各呼出しでJava内容SHA-256を確認し、同一内容のAST解析を再利用する。
削除とProject切替を反映し、canonical root単位で最大2つの揮発性cacheを保持する。
versionは走査したfile/path/content/statusのハッシュ、scannedAtは走査時刻。
非Java本文変更はversion対象外。buildは直列化し、cancelをfile単位で確認する。
FileSummaryCacheはLLM要約、RelatedFileGraphは読取中心の関係を扱うため、AST索引を混入しない。
自動全量prompt挿入やLLM要約の二重生成は行わない。
SubAgentは定義で明示要求した場合に利用でき、既存Tool permissionではREADに分類する。
