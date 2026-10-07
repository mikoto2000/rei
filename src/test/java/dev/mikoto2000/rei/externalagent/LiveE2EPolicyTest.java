package dev.mikoto2000.rei.externalagent;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
class LiveE2EPolicyTest {
  @Test void flagsDefaultOffAndRequireExplicitTrue(){assertFalse(LiveE2EPolicy.enabled(Map.of(),"REI_E2E_CODEX"));assertFalse(LiveE2EPolicy.enabled(Map.of("REI_E2E_CODEX","1"),"REI_E2E_CODEX"));assertTrue(LiveE2EPolicy.enabled(Map.of("REI_E2E_CODEX","true"),"REI_E2E_CODEX"));}
}
