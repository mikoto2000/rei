package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MultilanguageRepositoryMapTest {
  @TempDir Path root;List<String> paths=new ArrayList<>();
  void file(String path,String source)throws Exception{Path target=root.resolve(path);Files.createDirectories(target.getParent());Files.writeString(target,source);paths.add(path);}
  RepositoryMapService service(){return new RepositoryMapService(directory->List.copyOf(paths));}
  RepositoryMapService.File item(RepositoryMapService.View view,String path){return view.items().stream().filter(value->value.path().equals(path)).findFirst().orElseThrow();}
  void edge(RepositoryMapService.View view,String source,String target){assertTrue(view.relations().stream().anyMatch(value->value.source().equals(source)&&value.target().equals(target)&&value.kind().equals("HEURISTIC_IMPORT")),()->view.relations().toString());}
  @Test void typescriptAndJavascriptDeclarationsAndRelativeImportsAreExplicitlyHeuristic() throws Exception {
    file("client/package.json","{\"name\":\"client\"}");
    file("client/src/helper.ts","export interface Data { value: string }\nexport function help(): void {}\nexport type Alias = Data;");
    file("client/src/app.ts","import type { Data } from './helper.js';\nexport class App {\n  run(): void {}\n}\nexport const boot = () => new App();\n/* class Invented {} */\nconst text = `function Fake() {}`;");
    file("client/src/legacy.js","const helper = require('./helper.js');\nexport function legacy() {}\n");
    file("client/src/app.test.ts","import { App } from './app';\nfunction testApp() {}\n");
    var view=service().map(root,"",100);var app=item(view,"client/src/app.ts");
    assertEquals("TYPESCRIPT",app.language());assertEquals("HEURISTIC",app.analysisMode());assertEquals("HEURISTIC",app.status());assertEquals("client",app.module());assertEquals(64,app.sha256().length());
    assertTrue(app.symbols().stream().anyMatch(value->value.name().endsWith("App.run")&&value.kind().equals("METHOD")));
    assertTrue(app.symbols().stream().anyMatch(value->value.name().endsWith("boot")&&value.kind().equals("FUNCTION")));
    assertTrue(app.symbols().stream().noneMatch(value->value.name().contains("Fake")||value.name().contains("Invented")));
    edge(view,"client/src/app.ts","client/src/helper.ts");edge(view,"client/src/legacy.js","client/src/helper.ts");edge(view,"client/src/app.test.ts","client/src/app.ts");
    assertTrue(view.partial());assertTrue(view.warnings().stream().anyMatch(value->value.contains("heuristic")&&value.contains("resolver")));
    assertEquals(4,view.summary().heuristicFiles());assertEquals(0,view.summary().parsedJavaFiles());
  }
  @Test void rustUseGroupsModulesTypesAndMethodsProduceLocalStructuralCandidates() throws Exception {
    file("crate/Cargo.toml","[package]\nname = \"example\"\nversion = \"0.1.0\"\n");
    file("crate/src/worker.rs","pub struct Task {}\nimpl Task {\n pub fn run(&self) {}\n}\npub fn help() {}\n");
    file("crate/src/lib.rs","mod worker;\nuse crate::worker::{Task, help};\npub fn start() {}\n");
    file("crate/tests/worker.rs","use example::worker::Task;\n#[test]\nfn works() {}\n");
    var view=service().map(root,"",100);var worker=item(view,"crate/src/worker.rs");assertEquals("RUST",worker.language());assertEquals("crate",worker.module());
    assertTrue(worker.symbols().stream().anyMatch(value->value.name().endsWith("Task.run")&&value.kind().equals("METHOD")));
    edge(view,"crate/src/lib.rs","crate/src/worker.rs");edge(view,"crate/tests/worker.rs","crate/src/worker.rs");
  }
  @Test void goModulePackageGroupedImportsTypesAndReceiverMethodsStayWithinBuildBoundary() throws Exception {
    file("svc/go.mod","module example.test/app\ngo 1.24\n");
    file("svc/model/model.go","package model\ntype Item struct {}\nfunc (i *Item) Value() string { return \"\" }\n");
    file("svc/main.go","package main\nimport (\n \"example.test/app/model\"\n \"fmt\"\n)\nfunc main() {}\n");
    file("svc/model/model_test.go","package model_test\nimport m \"example.test/app/model\"\nfunc TestValue() {}\n");
    var view=service().map(root,"",100);var model=item(view,"svc/model/model.go");assertEquals("GO",model.language());assertEquals("model",model.packageName());assertEquals("svc",model.module());
    assertTrue(model.symbols().stream().anyMatch(value->value.name().endsWith("Item.Value")&&value.kind().equals("METHOD")));
    edge(view,"svc/main.go","svc/model/model.go");edge(view,"svc/model/model_test.go","svc/model/model.go");
  }
  @Test void pythonClassesMethodsFunctionsAndRelativeImportsHaveScopedCandidates() throws Exception {
    file("lib/pyproject.toml","[project]\nname = \"example\"\n");
    file("lib/pkg/model.py","class Item:\n    def value(self):\n        return 'result'\ndef load():\n    return Item()\n");
    file("lib/pkg/use.py","from .model import Item\nimport pkg.model\ndef use():\n    return Item()\n");
    file("lib/tests/test_model.py","from pkg.model import Item\ndef test_value():\n    assert Item().value()\n");
    var view=service().map(root,"",100);var model=item(view,"lib/pkg/model.py");assertEquals("PYTHON",model.language());assertEquals("lib",model.module());
    assertTrue(model.symbols().stream().anyMatch(value->value.name().endsWith("Item.value")&&value.kind().equals("METHOD")));
    edge(view,"lib/pkg/use.py","lib/pkg/model.py");edge(view,"lib/tests/test_model.py","lib/pkg/model.py");
  }
  @Test void impactUsesExistingReverseGraphAndLanguageTestConventionsWithoutCoverageClaims() throws Exception {
    file("package.json","{}");file("src/helper.ts","export function help() {}\n");file("src/app.ts","import { help } from './helper';\nexport function app() {}\n");file("src/app.test.ts","import { app } from './app';\n");
    var result=new ChangeTestImpactService(service()).analyze(root,List.of("src/helper.ts"),100);
    assertTrue(result.candidates().stream().anyMatch(value->value.path().equals("src/app.test.ts")&&value.testCandidate()&&value.distance()==2));
    assertTrue(result.partial());assertEquals("BROAD_REGRESSION_REQUIRED",result.regressionAssessment().scope());assertTrue(result.warnings().stream().anyMatch(value->value.contains("coverage")));
  }
  @Test void invalidLexicalSourceLimitsAndSecretsDoNotProduceInventedSymbols() throws Exception {
    file("bad.ts","/* unterminated comment\nexport class Invented {}\n");file("large.py","x".repeat(140000));file("secrets.rs","pub struct Secret {}\n");file("normal.js","export function visible() {}\n");
    var view=service().map(root,"",100);assertTrue(view.partial());assertTrue(item(view,"bad.ts").symbols().isEmpty());assertEquals("LEXICAL_ERROR",item(view,"bad.ts").status());assertEquals("TOO_LARGE",item(view,"large.py").status());assertTrue(view.items().stream().noneMatch(value->value.path().equals("secrets.rs")));
    var before=view.version();Files.writeString(root.resolve("normal.js"),"export function changed() {}\n");assertNotEquals(before,service().map(root,"",100).version());
  }
  @Test void manifestChangeInvalidatesCachedNamespaceEvenWithoutSourceEdit() throws Exception {
    file("workspace/pkg/app.ts","export function run() {}\n");var index=service();index.map(root,"",100);file("workspace/package.json","{}");
    var changed=item(index.map(root,"",100),"workspace/pkg/app.ts");assertEquals("workspace",changed.module());assertEquals("pkg.app.run",changed.symbols().getFirst().name());
  }
  @Test void ambiguousLocalImportDoesNotChooseAFileOrCrossAnotherBuildRoot() throws Exception {
    file("one/package.json","{}");file("one/src/helper.ts","export function help() {}\n");file("one/src/helper.js","export function help() {}\n");file("one/src/app.ts","import { help } from './helper';\nimport { other } from 'unknown-alias';\n");file("two/package.json","{}");file("two/src/helper.ts","export function other() {}\n");
    var view=service().map(root,"",100);assertTrue(view.relations().stream().noneMatch(value->value.source().equals("one/src/app.ts")));assertTrue(view.partial());
  }
  @Test void goModuleIdentityChangesRelationsAndVersionWithoutSourceEdits() throws Exception {
    file("go.mod","module example.test/app\n");file("model/model.go","package model\ntype Item struct {}\n");file("main.go","package main\nimport \"example.test/app/model\"\nfunc main() {}\n");
    var index=service();var before=index.map(root,"",100);edge(before,"main.go","model/model.go");Files.writeString(root.resolve("go.mod"),"module example.test/renamed\n");var after=index.map(root,"",100);
    assertTrue(after.relations().isEmpty());assertNotEquals(before.version(),after.version());
  }
  @Test void heuristicSourcesWorkWithoutJdkCompilerAndJavaMetadataStillPersistsInMixedProjects() throws Exception {
    file("App.java","class App {}\n");file("helper.ts","export function help() {}\n");
    var withoutCompiler=new RepositoryMapService(directory->paths,null).map(root,"",100);assertEquals("HEURISTIC",item(withoutCompiler,"helper.ts").status());assertEquals("COMPILER_UNAVAILABLE",item(withoutCompiler,"App.java").status());
    var data=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("index.db"));var metadata=new SqliteRepositoryMapIndex(data);var index=service();index.setPersistentIndex(metadata);index.map(root,"",100);assertEquals(Set.of("App.java"),metadata.load(root).keySet());
  }
  @Test void credentialBearingModuleSpecifiersDoNotExposeSourceSecrets() throws Exception {
    file("app.ts","import one from 'https://example.invalid/mod?token=PRIVATE_IMPORT_SECRET';\nimport two from 'https://user:PRIVATE_BASIC_SECRET@example.invalid/mod';\nexport function app() {}\n");
    String result=service().map(root,"",100).toString();assertFalse(result.contains("PRIVATE_IMPORT_SECRET"));assertFalse(result.contains("PRIVATE_BASIC_SECRET"));
  }
  @Test void pythonRelativeImportsCannotClimbOutsideTheirBuildRoot() throws Exception {
    file("one/pyproject.toml","[project]\nname='one'\n");file("one/pkg/app.py","from ...two.model import Item\n");file("two/pyproject.toml","[project]\nname='two'\n");file("two/model.py","class Item:\n    pass\n");
    var view=service().map(root,"",100);assertTrue(view.relations().stream().noneMatch(value->value.source().equals("one/pkg/app.py")));
  }
}
