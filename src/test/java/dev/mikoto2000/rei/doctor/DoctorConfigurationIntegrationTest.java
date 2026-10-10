package dev.mikoto2000.rei.doctor;

import java.nio.file.*;
import java.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import dev.mikoto2000.rei.voice.VoiceProperties;
import dev.mikoto2000.rei.externalagent.*;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class DoctorConfigurationIntegrationTest {
  @TempDir Path root;
  @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
  @EnableConfigurationProperties({VoiceProperties.class, CodexProperties.class, ClaudeCodeProperties.class, LlmProperties.class})
  static class BoundConfiguration {
    @Bean ModelHolderService currentModel() {return new ModelHolderService("MODEL-SECRET");}
    @Bean DoctorService doctor(Environment env, VoiceProperties voice, CodexProperties codex, ClaudeCodeProperties claude, LlmProperties llm) {
      return new DoctorService(env, voice, codex, claude, llm);
    }
    @Bean DoctorCommand command(DoctorService doctor) {return new DoctorCommand(doctor);}
  }
  @Test void actualBindingUsesFeatureOverrideAndCliWithoutRuntimeClients() {
    new ApplicationContextRunner().withUserConfiguration(BoundConfiguration.class)
        .withPropertyValues("spring.ai.openai.base-url=http://default.invalid", "rei.llm.features.chat.base-url=http://override.invalid",
            "rei.llm.features.chat.api-key=KEY-SECRET", "rei.voice.auto-start=true", "rei.voice.bundle-directory=" + root)
        .run(context -> {
          assertNull(context.getStartupFailure());
          var doctor = context.getBean(DoctorService.class);
          assertEquals("http://override.invalid", doctor.endpoint());
          var reports = doctor.passive(true);
          assertEquals(DoctorResult.Status.ERROR, reports.stream().filter(r -> r.id().equals("voice.bundle")).findFirst().orElseThrow().status());
          var out = new StringWriter(); var command = new picocli.CommandLine(context.getBean(DoctorCommand.class)); command.setOut(new PrintWriter(out));
          assertEquals(0, command.execute("--details"));
          assertTrue(out.toString().contains("rei.llm.features.chat.base-url"));
          assertFalse(out.toString().contains("KEY-SECRET")); assertFalse(out.toString().contains("MODEL-SECRET"));
          assertFalse(out.toString().contains("override.invalid"));
          assertEquals(0, context.getBeanNamesForType(dev.mikoto2000.rei.llm.LlmModelProvider.class).length);
          assertEquals(0, context.getBeanNamesForType(dev.mikoto2000.rei.voice.VoiceModelManager.class).length);
        });
  }
}
