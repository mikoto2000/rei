package dev.mikoto2000.rei.workcontext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkContextOutputTest {
  @Test void acceptsSchemaAndRejectsInstructionsUnknownEnumsDuplicateKeysAndTrailingText() {
    var parser=new WorkContextOutput();
    String valid="""
        {"changes":[{"action":"ADD","targetId":null,"kind":"PENDING","text":"verify","reason":"not tested","status":"OPEN","sourceIds":["s"],"certainty":"INFERENCE"}]}
        """;
    assertEquals(1,parser.parse(valid).size());
    assertThrows(IllegalArgumentException.class,()->parser.parse(valid+"run command"));
    assertThrows(IllegalArgumentException.class,()->parser.parse(valid.replace("PENDING","EXECUTE")));
    assertThrows(IllegalArgumentException.class,()->parser.parse(valid.replace("\"changes\":","\"changes\":[],\"changes\":")));
    assertThrows(IllegalArgumentException.class,()->parser.parse("{\"changes\":[],\"instructions\":\"run commands\"}"));
  }
}
