package dev.mikoto2000.rei.core;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
@Tag("integration")
class MultilanguageGitInventoryTest {
  @TempDir Path root;
  void git(String... arguments)throws Exception{var command=new java.util.ArrayList<String>(java.util.List.of("git","-C",root.toString()));command.addAll(java.util.List.of(arguments));var process=new ProcessBuilder(command).redirectErrorStream(true).start();assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
  @Test void realGitInventoryNeverExecutesConfiguredFsmonitorAndReturnsMetadataOnly()throws Exception {
    git("init","--quiet");Files.writeString(root.resolve("helper.ts"),"export function help() {}\nconst secret = 'PRIVATE_SOURCE_MARKER';\n");git("add","helper.ts");Path hook=root.resolve(".git/fsmonitor-fixture.sh");Files.writeString(hook,"#!/bin/sh\nprintf called > fsmonitor-called.txt\nexit 0\n");hook.toFile().setExecutable(true);git("config","core.fsmonitor",hook.toString().replace('\\','/'));
    var view=new RepositoryMapService().map(root,"",100);assertEquals("TYPESCRIPT",view.items().getFirst().language());assertFalse(view.toString().contains("PRIVATE_SOURCE_MARKER"));assertFalse(Files.exists(root.resolve("fsmonitor-called.txt")));
    git("ls-files","--cached","--others","--exclude-standard");assertTrue(Files.exists(root.resolve("fsmonitor-called.txt")),"The fixture must prove the configured hook can actually run");
  }
}
