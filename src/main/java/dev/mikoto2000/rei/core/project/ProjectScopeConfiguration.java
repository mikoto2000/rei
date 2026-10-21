package dev.mikoto2000.rei.core.project;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** プロジェクト対象範囲の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration
public class ProjectScopeConfiguration {
  /** プロジェクトごとの作業環境で用いるプロジェクト対象範囲Registrarを返す。 */
  @Bean
  public static BeanFactoryPostProcessor projectScopeRegistrar() {
    return factory -> {
      factory.registerScope("reiProject", new ProjectBeanScope());
      factory.registerScope(
          "reiConversation",
          new ProjectBeanScope(dev.mikoto2000.rei.llm.ConversationIds::currentChat));
    };
  }
}
