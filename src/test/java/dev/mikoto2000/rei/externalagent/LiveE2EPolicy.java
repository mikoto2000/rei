package dev.mikoto2000.rei.externalagent;
import java.util.Map;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
final class LiveE2EPolicy {
  static boolean enabled(Map<String,String> environment,String key){return "true".equalsIgnoreCase(environment.get(key));}
  static void require(String flag){assumeTrue(enabled(System.getenv(),flag),"LIVE_DISABLED: set "+flag+"=true explicitly");}
  static void account(String provider){require("REI_E2E_"+provider);assumeTrue(enabled(System.getenv(),"REI_E2E_"+provider+"_ACCOUNT_READY"),"ACCOUNT_NOT_CONFIRMED: attest existing subscription login with REI_E2E_"+provider+"_ACCOUNT_READY=true");}
  static void success(ExternalAgentResult result){assumeTrue(result.status()!=ExternalAgentResult.Status.UNAVAILABLE,"CLI_OR_CAPABILITY_UNAVAILABLE: live adapter unavailable");assumeTrue(!(result.status()==ExternalAgentResult.Status.REJECTED&&result.summary().startsWith("Claude Code subscription login required")),"SUBSCRIPTION_LOGIN_UNAVAILABLE: authenticate outside this test");org.junit.jupiter.api.Assertions.assertTrue(result.success(),"Live adapter outcome: "+result.status());}
}
