package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsPlanAppServerLauncherTest {
  @TempDir Path home;
  @org.springframework.boot.context.properties.EnableConfigurationProperties(PlanAppServerProperties.class)
  static class Binding {}
  @Test void configurationBindingSelectsNativeWindowsByDefault() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(Binding.class)
        .withPropertyValues("rei.chatgpt-plan.app-server.enabled=true","rei.chatgpt-plan.app-server.executable=C:/Codex/codex.exe")
        .run(context->{assertNull(context.getStartupFailure());assertEquals("windows",context.getBean(PlanAppServerProperties.class).executionMode());});
  }
  @Test void windowsIsDefaultAndRequiresNativeExe() {
    var p=new PlanAppServerProperties(true,home,null,"C:/Program Files/Codex/codex.exe",null);
    assertEquals("windows",p.executionMode());
    assertThrows(IllegalArgumentException.class,()->new PlanAppServerProperties(true,home,null,"/opt/codex","windows"));
    assertThrows(IllegalArgumentException.class,()->new PlanAppServerProperties(true,home,null,"C:/codex.cmd","windows"));
    assertThrows(IllegalArgumentException.class,()->new PlanAppServerProperties(true,home,null,"C:/codex.exe","auto"));
  }
  @Test void directProcessHasOwnHomeNoTokenArgumentsAndOnlyPlatformEnvironment() {
    var p=new PlanAppServerProperties(true,home,null,"C:/Program Files/Codex/codex.exe","windows");
    var launcher=new WindowsPlanAppServerLauncher(p);
    var child=launcher.process(home,home.resolve("workspace"),"selected-token",List.of("app-server","--listen","stdio://"));
    assertTrue(child.command().getFirst().endsWith("codex.exe"));
    assertFalse(child.command().toString().contains("selected-token"));
    assertFalse(child.command().toString().contains("wsl"));
    assertEquals("selected-token",child.environment().get("REI_PLAN_ACCESS_TOKEN"));
    assertEquals(home.toString(),child.environment().get("CODEX_HOME"));
    assertEquals(home.toString(),child.environment().get("USERPROFILE"));
    assertFalse(child.environment().containsKey("OPENAI_API_KEY"));
    assertFalse(child.environment().containsKey("WSLENV"));
    assertEquals(System.getenv("SystemDrive"),child.environment().get("SystemDrive"));
    String config=launcher.configuration();
    assertTrue(config.contains("sandbox = \"mxc\""));
    assertTrue(config.indexOf("web_search = \"disabled\"")<config.indexOf("[windows]"));
    assertTrue(config.contains("shell_tool = false"));
    assertTrue(config.contains("include_only"));
    assertFalse(config.contains("selected-token"));
    assertEquals("0.162.1",launcher.version());assertEquals("windows",launcher.platform());
  }
}
