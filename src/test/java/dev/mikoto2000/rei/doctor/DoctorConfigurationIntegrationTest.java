package dev.mikoto2000.rei.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.externalagent.ClaudeCodeProperties;
import dev.mikoto2000.rei.externalagent.CodexProperties;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.voice.VoiceProperties;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 環境診断機能の構成外部連携の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class DoctorConfigurationIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    VoiceProperties.class,
    CodexProperties.class,
    ClaudeCodeProperties.class,
    LlmProperties.class
  })
  static class BoundConfiguration {
    /** {@code currentModel} の条件を再現するための補助情報を返す。 */
    @Bean
    ModelHolderService currentModel() {
      return new ModelHolderService("MODEL-SECRET");
    }

    /** {@code doctor} の条件を再現するための補助情報を返す。 */
    @Bean
    DoctorService doctor(
        Environment env,
        VoiceProperties voice,
        CodexProperties codex,
        ClaudeCodeProperties claude,
        LlmProperties llm) {
      return new DoctorService(env, voice, codex, claude, llm);
    }

    /** {@code command} の条件を再現するための補助情報を返す。 */
    @Bean
    DoctorCommand command(DoctorService doctor) {
      return new DoctorCommand(doctor);
    }
  }

  /** 実際の関連付けについて、機能上書きを利用することを確認する。 */
  @Test
  void actualBindingUsesFeatureOverrideAndCliWithoutRuntimeClients() {
    new ApplicationContextRunner()
        .withUserConfiguration(BoundConfiguration.class)
        .withPropertyValues(
            "spring.ai.openai.base-url=http://default.invalid",
            "rei.llm.features.chat.base-url=http://override.invalid",
            "rei.llm.features.chat.api-key=KEY-SECRET",
            "rei.voice.auto-start=true",
            "rei.voice.bundle-directory=" + root)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var doctor = context.getBean(DoctorService.class);
              assertEquals("http://override.invalid", doctor.endpoint());
              var reports = doctor.passive(true);
              assertEquals(
                  DoctorResult.Status.ERROR,
                  reports.stream()
                      .filter(r -> r.id().equals("voice.bundle"))
                      .findFirst()
                      .orElseThrow()
                      .status());
              var out = new StringWriter();
              var command = new picocli.CommandLine(context.getBean(DoctorCommand.class));
              command.setOut(new PrintWriter(out));
              assertEquals(0, command.execute("--details"));
              assertTrue(out.toString().contains("rei.llm.features.chat.base-url"));
              assertFalse(out.toString().contains("KEY-SECRET"));
              assertFalse(out.toString().contains("MODEL-SECRET"));
              assertFalse(out.toString().contains("override.invalid"));
              assertEquals(
                  0,
                  context.getBeanNamesForType(dev.mikoto2000.rei.llm.LlmModelProvider.class)
                      .length);
              assertEquals(
                  0,
                  context.getBeanNamesForType(dev.mikoto2000.rei.voice.VoiceModelManager.class)
                      .length);
            });
  }
}
