package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class EpisodeOutputTest {
  @Test void emptySmallTalkResponse() {assertTrue(new EpisodeOutput().parse("{\"episodes\":[]}","s","p",List.of()).isEmpty());}
  @Test void refusesUnknownContinuation() {
    assertThrows(IllegalArgumentException.class,()->new EpisodeOutput().parse("""
        {"episodes":[{"id":"invented","occurredAt":"2026-10-11T00:00:00Z","status":"UNVERIFIED","title":"CLI","summary":"proposal","claims":[{"kind":"reason","text":"isolation","evidence":"MODEL_PROPOSAL","runId":"r","speaker":"assistant"}],"confidence":0.8}]}
        ""","s","p",List.of()));
  }
}
