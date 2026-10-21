#!/usr/bin/env python3
"""Render javac/ArchUnit TSVs with Python 3 stdlib; never update architecture budgets."""
import argparse
import collections
import csv
import hashlib
import html
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'dev.mikoto2000.rei.'


def read(name):
    with (ROOT / 'target/architecture' / name).open(encoding='utf-8', newline='') as f:
        return list(csv.DictReader(f, delimiter='\t', quoting=csv.QUOTE_NONE))


def feature(pkg):
    if pkg == PREFIX[:-1]: return '(root)'
    parts = pkg.removeprefix(PREFIX).split('.')
    return '.'.join(parts[:2]) if parts[0] in ('core', 'application') else parts[0]


def components(edges):
    graph = collections.defaultdict(set)
    for a, b in edges:
        graph[a].add(b)
        graph[b]
    index, low, stack, active, result = {}, {}, [], set(), []

    def visit(a):
        index[a] = low[a] = len(index)
        stack.append(a)
        active.add(a)
        for b in sorted(graph[a]):
            if b not in index:
                visit(b)
                low[a] = min(low[a], low[b])
            elif b in active:
                low[a] = min(low[a], index[b])
        if low[a] == index[a]:
            group = []
            while True:
                b = stack.pop()
                active.remove(b)
                group.append(b)
                if b == a:
                    break
            if len(group) > 1:
                result.append(sorted(group))

    for a in sorted(graph):
        if a not in index:
            visit(a)
    return sorted(result, key=lambda c: (-len(c), c))


def table(headers, rows):
    def cell(x):
        return html.escape(str(x), quote=False).replace('|', '\\|').replace('\n', '<br>')
    return '\n'.join(['| ' + ' | '.join(headers) + ' |', '| ' + ' | '.join(['---'] * len(headers)) + ' |']
                     + ['| ' + ' | '.join(map(cell, row)) + ' |' for row in rows]) + '\n'


def source_link(path, line=1):
    return f'[{path}](../../{path}#L{line})'


def category(name):
    if name in ('executeExternalProgram',): return 'Process'
    if name in ('repositoryMap',): return 'Search'
    if name in ('applyTextDiff', 'changeTestImpact', 'diagnoseTestReport', 'diagnoseTestReports'): return 'Document / Change'
    if re.search('goal|stage', name, re.I): return 'Goal'
    if re.search('project', name, re.I): return 'Project'
    if re.search('process|command|terminal', name, re.I): return 'Process'
    if re.search('search|grep|find|symbol|dependency', name, re.I): return 'Search'
    if re.search('document|render|repair|review|patch|changeset|artifact', name, re.I): return 'Document / Change'
    if re.search('file|directory|directories|read|write|edit|list|move|copy|delete|path', name, re.I): return 'File'
    return 'Other'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-commit', help='SHA of production sources (defaults to HEAD)')
    args = parser.parse_args()
    sha = subprocess.check_output(['git', 'rev-parse', args.source_commit or 'HEAD'], cwd=ROOT, text=True).strip()
    sources, classes, raw = read('sources.tsv'), read('classes.tsv'), read('dependencies.tsv')
    edges = {}
    for e in raw:
        key = (e['source'], e['target'], e['sourcePackage'], e['targetPackage'], e['kind'])
        if key not in edges or (':0)' in edges[key]['evidence'] and ':0)' not in e['evidence']):
            edges[key] = e
    edges = list(edges.values())
    production = [s for s in sources if s['path'].startswith('src/main/java/')]
    type_sizes = [s for s in read('type-sizes.tsv') if s['path'].startswith('src/main/java/')]
    packages = collections.Counter(s['package'] for s in production)
    counts = collections.Counter(c['package'] for c in classes)
    top = collections.Counter()
    for s in production:
        top['(root)' if s['package'] == PREFIX[:-1] else s['package'].removeprefix(PREFIX).split('.')[0]] += 1
    core = [s for s in production if s['package'] == PREFIX + 'core' or s['package'].startswith(PREFIX + 'core.')]
    resource_fixtures = sorted(p.relative_to(ROOT).as_posix()
                               for p in (ROOT / 'src/test/resources').rglob('*.java'))
    aggregate = collections.Counter((e['sourcePackage'], e['targetPackage'], e['kind']) for e in edges
                                    if e['sourcePackage'] != e['targetPackage'])
    fanin, fanout, classin = collections.defaultdict(set), collections.defaultdict(set), collections.defaultdict(set)
    for e in edges:
        if e['sourcePackage'] != e['targetPackage']:
            fanin[e['targetPackage']].add(e['sourcePackage'])
            fanout[e['sourcePackage']].add(e['targetPackage'])
            classin[e['target']].add(e['sourcePackage'])
    out = ROOT / 'docs/architecture'
    out.mkdir(parents=True, exist_ok=True)
    data = out / 'data'
    data.mkdir(exist_ok=True)
    # Deterministic digests exclude Phase 0 test/docs changes.
    digest = hashlib.sha256()
    for s in sorted(production, key=lambda s: s['path']):
        digest.update(s['path'].encode())
        digest.update((ROOT / s['path']).read_bytes().replace(b'\r\n', b'\n'))
    metadata = dict(sourceCommit=sha, productionSourceDigest=digest.hexdigest(),
                    productionFiles=len(production), productionPackages=len(packages),
                    productionBytecodeClasses=len(classes), coreFiles=len(core),
                    compiledJavaSources=len(sources), testResourceJavaFixtures=len(resource_fixtures),
                    inventoryCounts={k: sum(s['kind'] == k for s in sources)
                                     for k in ('production', 'test', 'generated')},
                    toolCount=len(read('tools.tsv')),
                    baselines={p.stem: len(p.read_text(encoding='utf-8').splitlines())
                               for p in sorted((ROOT / 'config/architecture').glob('*.txt'))})
    (data / 'summary.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    for name, rows, headers in [
        ('package-edges.tsv', [(a, b, k, n) for (a, b, k), n in sorted(aggregate.items())],
         ['sourcePackage', 'targetPackage', 'kind', 'classPairs']),
        ('sources.tsv', ([s[k] for k in ('path', 'package', 'types', 'lines', 'kind')] for s in sources),
         ['path', 'package', 'types', 'lines', 'kind']),
        ('class-sizes.tsv', ([s[k] for k in ('path', 'type', 'startLine', 'endLine', 'lines')] for s in type_sizes),
         ['path', 'type', 'startLine', 'endLine', 'lines']),
    ]:
        with (data / name).open('w', encoding='utf-8', newline='') as f:
            writer = csv.writer(f, delimiter='\t', lineterminator='\n')
            writer.writerow(headers)
            writer.writerows(rows)
    heading = f'\n分析対象コミット: `{sha}`。再実行方法は [README](README.md)。本番ソースSHA-256: `{digest.hexdigest()}`。\n\n'
    text = '# パッケージ依存の計測結果\n' + heading
    text += f'バックエンド本番: **{len(production)} Javaファイル / {len(packages)}パッケージ / {len(classes)} bytecodeクラス**。\n\n'
    text += 'ASTのtypesはネスト・匿名クラスを含む宣言数。bytecode数はLombok生成・匿名クラスを含み、ソース数とは一致しません。'
    text += 'terminalの共有ソースはバックエンド側で一度数え、terminal独自ソースのみ別計上します。生成ソースは対象ディレクトリに実在するファイルのみです。\n\n'
    text += table(['領域', 'Javaファイル'], sorted(collections.Counter(
        'backend production' if s['path'].startswith('src/main/java/') else
        'backend test' if s['path'].startswith('src/test/java/') else
        'generated' if s['kind'] == 'generated' else 'terminal ' + s['kind'] for s in sources).items()))
    text += f'\nコンパイル対象Javaソース総数（backend + terminal独自、重複除去）: **{len(sources)}**。生成.java: **{metadata["inventoryCounts"]["generated"]}**。\n'
    text += f'\n別途、コンパイル対象外のテストresource内Java fixtureは **{len(resource_fixtures)}** 件。両者の合計は **{len(sources) + len(resource_fixtures)}** 件です。\n'
    text += '\n' + '\n'.join('- ' + source_link(p) for p in resource_fixtures) + '\n'
    text += '\n## 主要パッケージ（トップレベル集約）\n\n' + table(['領域', 'ファイル数'], top.most_common())
    text += '\n## 大きなJavaファイル上位20（本番、コメント・空行込み）\n\n'
    text += table(['ファイル', '行数', 'AST型宣言数'], [(source_link(s['path']), s['lines'], s['types'])
        for s in sorted(production, key=lambda s: (-int(s['lines']), s['path']))[:20]])
    text += '\n## 大きなJavaクラス上位20（javac ASTの宣言開始〜終端、内部のコメント・空行込み）\n\n'
    text += 'ファイル全体の行数とは区別します。ネスト型はそれぞれ計測し、外側の型のspanにも含まれます。全本番型は [class-sizes.tsv](data/class-sizes.tsv)。\n\n'
    text += table(['クラス', 'ソース', 'クラスの行数'], [(s['type'].removeprefix(PREFIX),
        source_link(s['path'], s['startLine']), s['lines'])
        for s in sorted(type_sizes, key=lambda s: (-int(s['lines']), s['type']))[:20]])
    text += '\n## 全本番パッケージ\n\n' + table(['パッケージ', 'ファイル数', 'bytecodeクラス', 'fan-in / fan-out（異なるpackage数）'],
        [(p.removeprefix(PREFIX), n, counts[p], f'{len(fanin[p])} / {len(fanout[p])}') for p, n in sorted(packages.items())])
    for label, metric in [('参照先の集中（fan-in）', fanin), ('依存先の広さ（fan-out）', fanout)]:
        text += '\n## ' + label + ' 上位10\n\n'
        text += table(['パッケージ', '異なるpackage数'], [(p.removeprefix(PREFIX), len(values))
            for p, values in sorted(metric.items(), key=lambda item: (-len(item[1]), item[0]))[:10]])
    text += '\n## 参照されるクラス上位20（異なる参照元パッケージ数）\n\n'
    text += table(['クラス', '参照元パッケージ数'], [(c.removeprefix(PREFIX), len(v))
        for c, v in sorted(classin.items(), key=lambda x: (-len(x[1]), x[0]))[:20]])
    text += '\n## Configuration配置（注釈・メタ注釈・包含クラスにより判定）\n\n'
    text += table(['クラス', '外向き内部クラス依存数'], [(c['class'].removeprefix(PREFIX),
        sum(e['source'] == c['class'] for e in edges)) for c in classes if c['wiring'] == 'true'])
    text += '\n全package辺は [package-edges.tsv](data/package-edges.tsv)。class辺・行番号付き証拠は `target/architecture/dependencies.tsv` に再生成します。'
    text += 'wiringはConfigurationを依存元とする辺、businessはそれ以外。`@ConfigurationProperties`はConfigurationとは区別します。'
    text += 'Bean factoryメソッドを通常の@Component内に置く場合や、Configuration内の業務処理はこの二分類で完全には判別できず、手動確認が必要です。\n'
    (out / 'package-dependencies.md').write_text(text, encoding='utf-8', newline='\n')
    cycles = '# 循環依存\n' + heading
    cycle_details = []
    cycles += '以下は強連結成分（SCC）。SCC全体が一本の単純な循環を意味するわけではありません。2点相互参照と3点以上の経路を同じ到達可能性で検出します。'
    cycles += 'ゲートはSCC名ではなく、循環に参加する個々のクラス辺を固定します。既存SCC内に追加する辺も失敗します。\n\n'
    for level in ('feature', 'package'):
        for wiring in (False, True):
            selected = [e for e in edges if wiring or e['kind'] == 'business']
            group = (lambda p: feature(p)) if level == 'feature' else (lambda p: p.removeprefix(PREFIX))
            graph_edges = {(group(e['sourcePackage']), group(e['targetPackage'])) for e in selected}
            sccs = components(graph_edges)
            cycles += f'## {level} / {"配線を含む全依存" if wiring else "処理本体のみ"} — {len(sccs)} SCC\n\n'
            for i, scc in enumerate(sccs, 1):
                cycles += f'### SCC {i}（{len(scc)}境界）\n\n' + ', '.join(f'`{s}`' for s in scc) + '\n\n'
                representatives = {}
                for e in sorted(selected, key=lambda e: (':0)' in e['evidence'], e['source'], e['target'])):
                    a, b = group(e['sourcePackage']), group(e['targetPackage'])
                    if a != b and a in scc and b in scc:
                        representatives.setdefault((a, b), e)
                ordered = sorted(representatives.items())
                cycle_details.extend((level, 'all' if wiring else 'business', i, a, b, e['kind'],
                                      e['source'], e['target'], e['evidence']) for (a, b), e in ordered)
                cycles += '各方向の全代表クラスと証拠は [cycle-edges.tsv](data/cycle-edges.tsv)。以下は先頭8方向です。\n\n'
                cycles += table(['方向', '分類', '代表的な依存元 → 依存先（証拠）'],
                    [(f'{a} → {b}', e['kind'], e['evidence']) for (a, b), e in ordered[:8]])
    with (data / 'cycle-edges.tsv').open('w', encoding='utf-8', newline='') as f:
        writer = csv.writer(f, delimiter='\t', lineterminator='\n')
        writer.writerow(['level', 'scope', 'SCC', 'from', 'to', 'kind', 'source', 'target', 'evidence'])
        writer.writerows(cycle_details)
    cycles += '\n## 解釈と優先順位（分析・提案）\n\n'
    cycles += 'P1: AgentRunConfiguration / WebApiConfiguration / AiConfiguration の横断配線をbootstrapへ集める。'
    cycles += 'P2: core直下のTools・アプリケーションサービスから各機能への呼び出しと、各機能からcoreのRun/Project/Policy型への逆参照を分ける。'
    cycles += 'P3: Run・Sessionとconversationの受付・保存・実行境界を整理する。'
    cycles += '原因は共有コンテキストと実行・保存の相互協調にあるとの推定であり、循環であることだけで実行時障害とは判断しません。'
    cycles += '具体的な図と移行の完了条件は [README](README.md) と [移行計画](migration-roadmap.md)。\n'
    (out / 'dependency-cycles.md').write_text(cycles, encoding='utf-8', newline='\n')
    responsibilities = '# coreの責務\n' + heading
    responsibilities += f'`core`配下は{len(core)}ファイル（本番の{len(core)/len(production):.1%}）。一枚のdomain層とは扱いません。\n\n'
    coregroups = collections.Counter(feature(s['package']) for s in core)
    responsibilities += table(['境界', 'ファイル数'], sorted(coregroups.items()))
    responsibilities += '\n- `core.chat`: 実行コンテキスト・会話ループ・配線。`application.run`の受付・状態管理と協働。\n'
    responsibilities += '- `core.command` / `completion`: CLI解釈・補完。UI寄りの責務を含む。\n'
    responsibilities += '- `core.policy` / `stagnation` / `checkpoint`: 権限、実行進捗、継続コンテキスト。\n'
    responsibilities += '- `core.project` / `datasource`: Project所有権・保存先。\n'
    responsibilities += '- `core.contextbudget` / `working` / `recentchanges` / `filesummary` / `relatedgraph`: 文脈組み立てと配信・キャッシュ。\n'
    responsibilities += '- `core.process` / `service`: プロセス実行とOS連携。\n'
    responsibilities += '- `core.execution`: バックグラウンド実行の管理と表示・通知への橋渡し。`core.dependency`: 待機条件と依存の永続管理。\n'
    responsibilities += '- `core.actionplan` / `taskstate`: 実行計画・タスク状態の文脈化。`core.predicate`: 宣言的な条件評価。\n'
    responsibilities += '- `core.searchcache`: 検索結果キャッシュ。`core.diagnostic`: 失敗診断。`core.sqlitevec`: ネイティブ拡張のplatform判定・取得・ロード。\n'
    responsibilities += '- `core.configuration`: モデル・DB等の横断配線と設定。\n'
    responsibilities += '- `core`直下: Tools、文書・レビュー・修復・ファイル操作等のサービス。単一責務ではない。\n\n'
    responsibilities += '上記は名称とコードからの責務分類。全クラスのpackageと規模は [集計](package-dependencies.md)、全ソースは [sources.tsv](data/sources.tsv)。'
    responsibilities += 'core内部の機能間辺も循環検査の対象です。Eゲートの予算はcore外部→coreのクラス辺、内部集中はpackage辺のfan-in/outで別に観測します。\n'
    (out / 'core-responsibilities.md').write_text(responsibilities, encoding='utf-8', newline='\n')
    tools, fields = read('tools.tsv'), read('tools-fields.tsv')
    toolpath = 'src/main/java/dev/mikoto2000/rei/core/Tools.java'
    tooltext = '# Tools.javaの構造\n' + heading
    tooltext += f'`@Tool`メソッドは **{len(tools)}件**。名前・説明は実際の実行時注釈、引数・戻り値はjavac ASTから取得しています。'
    tooltext += '分類は名前に基づく移行検討用の暫定分類で、権限分類ではありません。Service等の列はメソッド本体に名前が現れるフィールド候補です（名前解決は行わず、間接呼び出しはソース参照）。\n\n'
    tooltext += table(['分類', '件数'], sorted(collections.Counter(category(t['name']) for t in tools).items()))
    tooltext += '\n## 全公開ツール\n\n'
    tooltext += table(['ツール名 / ソース', '分類', '引数・戻り値', '説明', '直接参照フィールド'], [
        (f"[{t['name']}](../../{toolpath}#L{t['line']})", category(t['name']), t['signature'], t['annotation'],
         ', '.join(f['name'] for f in fields if re.search(r'\b' + re.escape(f['name']) + r'\b', t['body']))) for t in tools])
    tooltext += '\n## 共有フィールド・内部状態\n\n'
    tooltext += table(['フィールド', '型', 'ソース'], [(f['name'], f['type'], source_link(toolpath, f['line'])) for f in fields])
    tooltext += '\n## 公開・Policy・Runと分割方針\n\n'
    tooltext += ('`AiConfiguration.chatClient` が `DeliveryToolRegistration.provider(tools)` を登録します。'
        'MethodToolCallbackProviderによる注釈検出と配信種別のwrapperが公開契約を構成します。'
        '`Tools.diagnosedOwner`やToolContext内RunExecutionContextがProject/root/session/Runの所有者を拘束し、'
        '`ChatExecutionService`から設定されるToolPermissionGuardが実行経路で権限を確認します。'
        'Toolsだけを呼ぶテストではこの経路全体の承認・配信・所有権を保証できません。\n\n'
        'Phase 2ではRegistry/Provider（収集・公開）、LLM Adapter（固定名・schema・description）、'
        'Application Service（所有者・Policyとuse case）、Infrastructure Service（filesystem/process/Tika/renderer）を分離する案です。'
        'まずFile/Search、Process、Document/Change、Goal/Projectを別PRにし、共有キャッシュ・イベント・Run状態を明示的な依存として渡します。\n\n'
        '必要な契約テスト: 実登録callback全件のname/description/input JSON Schema/returnDirect/配信種別のsnapshot、'
        '重複・欠落・無効機能、旧JSON入力と戻り値、未知・不正引数、承認拒否/許可、read-only/full-auto、'
        '異なるRun/root/sessionの拒否、cancel/timeout、raw-result/event/redactionを登録から実行まで比較する。\n\n')
    tests = sorted(p.relative_to(ROOT).as_posix() for p in (ROOT / 'src/test/java').rglob('*.java')
                   if re.search(r'\bTools\b|DeliveryToolRegistration|ToolPermissionGuard', p.read_text(encoding='utf-8')))
    tooltext += '## 関連テスト配置\n\n' + '\n'.join('- ' + source_link(p) for p in tests) + '\n\n'
    tooltext += 'カバレッジ計測は未導入で、上記は参照の所在であり実行網羅率ではありません。今回のPhase 0はツール実装・公開仕様を変更していません。\n'
    (out / 'tools-analysis.md').write_text(tooltext, encoding='utf-8', newline='\n')
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
