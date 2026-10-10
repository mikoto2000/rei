package dev.mikoto2000.rei.voice;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.application.input.*;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.conversation.*;

class VoiceCorrectionContextTest {
  @TempDir Path root;
  @Test void projectTermsOverrideCommonReadingsAndMalformedFilesDoNotStopVoice() throws Exception {
    var common=root.resolve("common.json");var project=root.resolve("project.json");
    Files.writeString(common,"[{\"canonical\":\"Ray\",\"reading\":\"れい\"},{\"canonical\":\"Whisper\"}]");
    Files.writeString(project,"[{\"canonical\":\"Rei\",\"reading\":\"れい\",\"aliases\":[\"レイ\"]}]");
    assertThat(VoiceCorrectionContext.dictionaries(common,project,64)).extracting(VoiceCorrectionContext.Term::canonical).containsExactly("Rei","Whisper");
    Files.writeString(project,"invalid");assertThat(VoiceCorrectionContext.dictionaries(common,project,1)).hasSize(1);
  }
  @Test void onlyCapturedSessionHistoryIsUsedWithCharacterBudgetAndRedaction() {
    var logs=mock(ConversationLogStore.class);var p=new VoiceCorrectionProperties();p.setMaxContextChars(40);
    var target=new ConversationTarget(new ProjectContext(UUID.randomUUID().toString(),"Rei",root),"captured-session");
    when(logs.recentConversation(target.project().id(),target.sessionId(),4)).thenReturn(List.of(
        new ConversationLogEntry(target.sessionId(),"chat","user",java.time.OffsetDateTime.now(),"password=topsecret 微分の話",1)));
    var context=new VoiceCorrectionContext(logs,root,p).capture(new ConversationInput(UUID.randomUUID(),InputSource.VOICE,target,"交配の話",Instant.now()));
    assertThat(context.history().toString()).doesNotContain("topsecret");assertThat(context.history().getFirst().length()).isLessThanOrEqualTo(40);
    verify(logs).recentConversation(target.project().id(),target.sessionId(),4);verifyNoMoreInteractions(logs);
  }
}
