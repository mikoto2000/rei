package dev.mikoto2000.rei.artifact;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.web.ArtifactController;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"rei.web.enabled","rei.artifacts.enabled"},havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ArtifactProperties.class)
@Import(ArtifactController.class)
public class ArtifactConfiguration {
  @Bean ArtifactSourceExport artifactSourceExport(ArtifactStore store,ProjectRegistry projects,
      dev.mikoto2000.rei.application.session.SessionLifecycle sessions,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.core.TextChangeSetService> changes,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.paper.PaperLibraryService> papers) {
    return new ArtifactSourceExport(store,projects,sessions,changes.getIfAvailable(),papers.getIfAvailable());
  }
  @Bean ArtifactStore artifactStore(@org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource") javax.sql.DataSource source,
      ProjectRegistry projects,Clock clock,ArtifactProperties properties,@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    return new ArtifactStore(source,projects,Path.of(directory).resolve("artifacts"),clock,properties);
  }
}
