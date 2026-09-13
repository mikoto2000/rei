package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionHistoryTest {
  @Test void nextDecisionReceivesKeysScrollDirectionAndWaitDuration(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    var actions=List.<ComputerAction>of(new ComputerAction.PressKey("TAB",ComputerAction.Risk.LOW),
        new ComputerAction.Scroll(-3,ComputerAction.Risk.LOW),new ComputerAction.Scroll(2,ComputerAction.Risk.LOW),
        new ComputerAction.Wait(650));
    var dispatched=new java.util.ArrayList<ComputerAction>();
    var service=new ComputerUseService(ComputerUseServiceTest::screen,o->{
      if(o.step()==5) {
        var h=o.recentHistory();
        assertEquals(4,h.size());
        assertTrue(h.get(0).contains("\"key\":\"TAB\""));
        assertTrue(h.get(1).contains("\"amount\":-3"));
        assertTrue(h.get(1).contains("\"direction\":\"up\""));
        assertTrue(h.get(2).contains("\"direction\":\"down\""));
        assertTrue(h.get(3).contains("\"millis\":650"));
        assertTrue(h.stream().allMatch(s->s.contains("goal not verified")));
        return new ComputerAction.Done("visible");
      }
      return actions.get(o.step()-1);
    },(a,s)->dispatched.add(a),a->{},SafetyPolicy.lowRiskOnly(),()->false,e->{},5,5,new ComputerDiagnostics(dir));
    assertEquals(ComputerUseResult.Status.DONE,service.run("goal").status());
    assertEquals(actions.subList(0,3),dispatched);
    java.nio.file.Path run;
    try(var paths=java.nio.file.Files.list(dir)) { run=paths.findFirst().orElseThrow(); }
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    assertEquals("TAB",json.readTree(run.resolve("step-001/dispatched.json").toFile()).get("key").asText());
    var scroll=json.readTree(run.resolve("step-002/dispatched.json").toFile());
    assertEquals(-3,scroll.get("amount").asInt());
    assertEquals("up",scroll.get("direction").asText());
    assertEquals("wheel_notches",scroll.get("unit").asText());
    assertEquals(650,json.readTree(run.resolve("step-004/decided.json").toFile()).get("millis").asLong());
    assertFalse(java.nio.file.Files.exists(run.resolve("step-004/dispatched.json")));
  }
}
