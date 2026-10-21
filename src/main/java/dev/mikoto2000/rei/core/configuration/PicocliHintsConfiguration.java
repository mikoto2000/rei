package dev.mikoto2000.rei.core.configuration;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/** Picocli案内一覧の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration
@ImportRuntimeHints(PicocliRuntimeHints.class)
public class PicocliHintsConfiguration {}

/** 機能の組み立てに必要なPicocli実行環境案内一覧を扱う。 */
class PicocliRuntimeHints implements RuntimeHintsRegistrar {
  /** 後から対象を参照できるよう、案内一覧を登録する。 */
  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints
        .reflection()
        .registerType(
            picocli.CommandLine.class,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.ACCESS_DECLARED_FIELDS);

    hints
        .reflection()
        .registerType(
            picocli.CommandLine.HelpCommand.class,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.ACCESS_DECLARED_FIELDS);
  }
}
