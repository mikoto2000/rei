package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.memory.service.MemoryOutput;
import dev.mikoto2000.rei.memory.model.MemoryType;

class MemoryOutputTest {
  static final String VALID="""
      {"memories":[{"type":"LESSON","scope":"PROJECT","content":"Caps Lock was unreliable in this environment",
      "summary":"Hotkey lesson","confidence":0.9,"importance":0.8,"sourceTurnIds":["t"],"tags":["hotkey"]}]}
      """;
  @Test void parsesGroundedLesson() { assertEquals(MemoryType.LESSON,new MemoryOutput().candidates(VALID).getFirst().type()); }
  @Test void rejectsSchemaViolations() {
    for(String value:new String[]{VALID.replace("0.9","1.9"),VALID.replace("LESSON","UNKNOWN"),
        VALID.replace("[\"t\"]","[]"),VALID.replace("\"PROJECT\"","\"PERMANENT\""),"[]","{\"memories\":null}"})
      assertThrows(IllegalArgumentException.class,()->new MemoryOutput().candidates(value));
  }
  @Test void rejectsFencesTrailingValuesAndDuplicateKeys() {
    for(String value:new String[]{"```json\n"+VALID+"```",VALID+"{}","{\"memories\":[],\"memories\":[]}"})
      assertThrows(RuntimeException.class,()->new MemoryOutput().candidates(value));
  }
  @Test void emptyCandidatesAreValid() { assertTrue(new MemoryOutput().candidates("{\"memories\":[]}").isEmpty()); }
  @Test void resolutionRequiresKnownActionAndNoAdditionalFields() {
    assertThrows(IllegalArgumentException.class,()->new MemoryOutput().resolution("{\"action\":\"DELETE\",\"targetIds\":[]}"));
    assertThrows(IllegalArgumentException.class,()->new MemoryOutput().resolution("{\"action\":\"NEW\",\"targetIds\":[],\"extra\":true}"));
  }
}
