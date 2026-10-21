package dev.mikoto2000.rei.artifact;

import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.web.ArtifactController;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 成果物の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.artifacts.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ArtifactProperties.class)
@Import(ArtifactController.class)
public class ArtifactConfiguration {
  /** 成果物の管理で用いる成果物取得元書き出しを返す。 */
  @Bean
  ArtifactSourceExport artifactSourceExport(
      ArtifactStore store,
      ProjectRegistry projects,
      dev.mikoto2000.rei.application.session.SessionLifecycle sessions,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.core.TextChangeSetService>
          changes,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.paper.PaperLibraryService>
          papers) {
    return new ArtifactSourceExport(
        store, projects, sessions, changes.getIfAvailable(), papers.getIfAvailable());
  }

  /** 成果物の管理で用いる成果物保存先を返す。 */
  @Bean
  ArtifactStore artifactStore(
      @org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource")
          javax.sql.DataSource source,
      ProjectRegistry projects,
      Clock clock,
      ArtifactProperties properties,
      @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    return new ArtifactStore(
        source, projects, Path.of(directory).resolve("artifacts"), clock, properties);
  }
}
