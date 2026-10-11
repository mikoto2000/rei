package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class EpisodePeriodTest {
  @Test void anUnknownEndRemainsUnknownAndAnInvertedPeriodIsRejected() {
    var claims=List.of(new Episode.Claim("unknown","result unknown",Episode.Evidence.UNVERIFIED,"r","assistant"));
    var ongoing=new Episode("e","p","s","v","2026-10-01T00:00:00Z",null,Episode.Status.IN_PROGRESS,"title","summary",claims,.5);
    assertNull(ongoing.endedAt());
    assertThrows(IllegalArgumentException.class,()->new Episode("e","p","s","v","2026-10-01T00:00:00Z","2026-09-30T00:00:00Z",Episode.Status.COMPLETED,"title","summary",claims,.5));
  }
}
