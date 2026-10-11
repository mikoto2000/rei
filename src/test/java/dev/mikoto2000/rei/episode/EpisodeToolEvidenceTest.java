package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
class EpisodeToolEvidenceTest {
  @Test void toolEvidenceRequiresExactRetainedEventAndRun() {
    var repository=mock(EpisodeRepository.class);var extractor=mock(EpisodeExtractor.class);var events=mock(ProjectAgentEventStore.class);
    var turn=new ConversationTurnStore.Turn("r","test CLI",ConversationTurnStore.Status.COMPLETED,"passed",Instant.EPOCH);
    var claim=new Episode.Claim("result","3 tests passed",Episode.Evidence.TOOL_OBSERVED,"r","tool","event1");
    var episode=new Episode("e","p","s","v",Instant.EPOCH.toString(),Episode.Status.COMPLETED,"CLI tests","Tests passed",List.of(claim),1);
    var event=new AgentEvent("event1",1,Instant.EPOCH,AgentEventType.TOOL_COMPLETED,1,"s","r","r",null,null,new ToolCompletedPayload("call","test",1,"3 tests passed",null,null,null,null),"p");
    when(events.recent("p",1000)).thenReturn(List.of(event));
    when(events.findEvent("p","event1")).thenReturn(Optional.of(event));
    when(extractor.extract(eq("s"),eq("p"),anyList(),anyList(),isNull())).thenReturn(List.of(episode));
    var processor=new EpisodeProcessor(repository,extractor);processor.setEvents(events);
    processor.process("s","p",0,1,List.of(turn),null);verify(repository).saveBatch("s",0,1,List.of(episode));
    when(events.recent("p",1000)).thenReturn(List.of());
    when(events.findEvent("p","event1")).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class,()->processor.process("s","p",0,1,List.of(turn),null));
  }
}
