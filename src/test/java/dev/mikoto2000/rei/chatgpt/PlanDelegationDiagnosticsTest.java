package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanDelegationDiagnosticsTest {
  final PlanDelegationProperties settings=new PlanDelegationProperties(true,Set.of("default"),Set.of("model"));
  @Test void missingAndDisallowedDestinationsHaveDistinctSafeReasons() {
    assertTrue(assertThrows(ChatGptException.class,()->settings.check(null,"model")).getMessage().contains("ACCOUNT_REQUIRED"));
    assertTrue(assertThrows(ChatGptException.class,()->settings.check("default",null)).getMessage().contains("MODEL_REQUIRED"));
    var account=assertThrows(ChatGptException.class,()->settings.check("private-account","model"));
    assertTrue(account.getMessage().contains("ACCOUNT_NOT_ALLOWED"));assertFalse(account.getMessage().contains("private-account"));
    assertTrue(assertThrows(ChatGptException.class,()->settings.check("default","other")).getMessage().contains("MODEL_NOT_ALLOWED"));
    assertDoesNotThrow(()->settings.check("default","model"));
  }
}
