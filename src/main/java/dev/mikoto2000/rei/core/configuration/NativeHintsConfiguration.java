package dev.mikoto2000.rei.core.configuration;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.ai.vectorstore.SimpleVectorStoreContent;

@Configuration
@ImportRuntimeHints(VectorStoreRuntimeHints.class)
public class NativeHintsConfiguration {
}

class VectorStoreRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.reflection().registerType(
        SimpleVectorStoreContent.class,
        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
        MemberCategory.ACCESS_DECLARED_FIELDS,
        MemberCategory.INVOKE_DECLARED_METHODS,
        MemberCategory.INVOKE_PUBLIC_METHODS
    );
  }
}
