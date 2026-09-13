package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TextInputHistoryTest {
  static String focus(String id,String value) {
    var n=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    n.put("status","ok").put("hasKeyboardFocus",true).put("enabled",true).put("editable",true)
        .put("password",false).put("elementId",id).put("valueStatus","ok").put("value",value);
    return n.toString();
  }
  @Test void duplicateOrUnverifiableTypingIsBlockedButDistinctFieldsAndVerifiedEmptyRetryAreAllowed() {
    var ledger=new TextInputHistory();
    ledger.dispatched("こんにちは！世界！",focus("firefox:1",""));
    for (String f:java.util.List.of(focus("firefox:1","こんにちは！世界！"),focus("firefox:1","prefixこんにちは！世界！"),
        focus("firefox:1","partial"),WindowsFocusProbe.UNKNOWN,
        focus("firefox:1","").replace("\"valueStatus\":\"ok\"","\"valueStatus\":\"truncated\"")))
      assertThrows(InvalidComputerDecision.class,()->ledger.check("こんにちは！世界！",f));
    assertDoesNotThrow(()->ledger.check("こんにちは！世界！",focus("firefox:1","")));
    assertDoesNotThrow(()->ledger.check("こんにちは！世界！",focus("firefox:2","")));
    assertDoesNotThrow(()->ledger.check("different",focus("firefox:1","こんにちは！世界！")));
    var unknown=new TextInputHistory();
    unknown.dispatched("hello",WindowsFocusProbe.UNKNOWN);
    assertThrows(InvalidComputerDecision.class,()->unknown.check("hello",focus("new","")));
  }
  @Test void servicePassesTypedTextToPlannerAndPreventsSecondPaste() {
    var inputs=new java.util.concurrent.atomic.AtomicInteger();
    var service=new ComputerUseService(ComputerUseServiceTest::screen,o->{
      if(o.step()==2) {
        assertTrue(o.recentHistory().getFirst().contains("\"typedText\":\"こんにちは！世界！\""));
        assertTrue(o.focusState().contains("こんにちは！世界！"));
      }
      return new ComputerAction.TypeText("こんにちは！世界！",ComputerAction.Risk.LOW);
    },(a,s)->inputs.incrementAndGet(),a->{},SafetyPolicy.lowRiskOnly(),()->false,e->{},3,1,
      new ComputerDiagnostics(null),()->focus("firefox:1",inputs.get()==0 ? "" : "こんにちは！世界！"));
    var result=service.run("enter text");
    assertEquals(ComputerUseResult.Status.MODEL_ERROR,result.status());
    assertEquals(1,inputs.get());
    assertTrue(result.reason().contains("Duplicate text input blocked"));
  }
}
