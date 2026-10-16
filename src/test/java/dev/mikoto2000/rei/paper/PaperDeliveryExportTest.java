package dev.mikoto2000.rei.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperDeliveryExportTest {
  @TempDir Path root;

  @Test
  void exportsOnlyTheRequestedCachedVersionWithoutGeneratingAnother() {
    var repo = mock(PaperRepository.class);
    var config = new PaperProperties();
    config.setEnabled(true);
    var paper = mock(Paper.class);
    String id = "12345678-1234-1234-1234-123456789012";
    when(paper.id()).thenReturn(id);
    when(repo.find(id)).thenReturn(Optional.of(paper));
    when(repo.artifact(id, "summary", "version")).thenReturn(Optional.of("cached summary"));
    var library =
        new PaperLibraryService(
            repo, new PaperArtifactStore(root, config), new PaperSessionReferences(), config);
    var exported = library.exportCached(id, "summary", "version", PaperOperation.local("session"));
    assertThat(new String(exported.bytes(), java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo("cached summary");
    assertThatThrownBy(
            () -> library.exportCached(id, "summary", "missing", PaperOperation.local("session")))
        .isInstanceOf(PaperException.class);
    verify(repo, never()).saveArtifact(anyString(), anyString(), anyString(), anyString(), any());
  }
}
