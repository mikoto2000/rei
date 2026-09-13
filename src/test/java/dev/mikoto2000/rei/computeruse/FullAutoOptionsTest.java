package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class FullAutoOptionsTest {
  @Test void explicitFlagSelectsPolicyAndInvalidValuesFailStartup() {
    for (String[] args:java.util.List.of(new String[]{},new String[]{"--fullauto=false"})) {
      var options=new FullAutoOptions(new DefaultApplicationArguments(args));
      assertFalse(options.enabled());
      assertFalse(new ComputerUseConfiguration().computerSafetyPolicy(options).allows(action(ComputerAction.Risk.CONFIRM_REQUIRED)));
    }
    for(String flag:java.util.List.of("--fullauto","--fullauto=true")) {
      var options=new FullAutoOptions(new DefaultApplicationArguments(flag));
      var policy=new ComputerUseConfiguration().computerSafetyPolicy(options);
      assertTrue(options.enabled());
      assertTrue(policy.allows(action(ComputerAction.Risk.LOW)));
      assertTrue(policy.allows(action(ComputerAction.Risk.CONFIRM_REQUIRED)));
      assertFalse(policy.allows(action(ComputerAction.Risk.PROHIBITED)));
      assertTrue(options.startupMessage().contains("PROHIBITED remains blocked"));
    }
    assertThrows(IllegalArgumentException.class,()->new FullAutoOptions(new DefaultApplicationArguments("--fullauto=maybe")));
  }
  @Test void serviceDispatchesConfirmationOnlyInFullAutoAndNeverProhibited() {
    for(boolean enabled:java.util.List.of(false,true)) for(var risk:ComputerAction.Risk.values()) {
      var count=new java.util.concurrent.atomic.AtomicInteger();
      var policy=new ComputerUseConfiguration().computerSafetyPolicy(new FullAutoOptions(
          new DefaultApplicationArguments("--fullauto="+enabled)));
      var service=new ComputerUseService(ComputerUseServiceTest::screen,
          o->o.step()==1 ? action(risk) : new ComputerAction.Done("visible"),(a,s)->count.incrementAndGet(),
          a->{},policy,()->false,e->{},2,2);
      var result=service.run("goal");
      boolean allowed=risk==ComputerAction.Risk.LOW || enabled && risk==ComputerAction.Risk.CONFIRM_REQUIRED;
      assertEquals(allowed ? 1 : 0,count.get());
      assertEquals(allowed ? ComputerUseResult.Status.DONE : ComputerUseResult.Status.SAFETY_BLOCKED,result.status());
    }
  }
  private ComputerAction action(ComputerAction.Risk risk) { return new ComputerAction.PressKey("ENTER",risk); }
}
