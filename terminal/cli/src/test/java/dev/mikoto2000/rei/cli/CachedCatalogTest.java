package dev.mikoto2000.rei.cli;
import static org.junit.jupiter.api.Assertions.*;
import dev.mikoto2000.rei.application.cli.CliCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
class CachedCatalogTest {
  @Test void completesNestedAliasesOptionsAndEnumValuesEntirelyFromImmutableData() {
    var option=new CliCatalog.Option(List.of("--mode"),"Mode","EXCLUSIVE","1",false,List.of("EXCLUSIVE","READ_ONLY"));
    var command=new CliCatalog.Command("switch",List.of("resume"),"Select",List.of(option),List.of(),List.of());
    var root=new CliCatalog.Command("session",List.of(),"Sessions",List.of(),List.of(),List.of(command));
    var cache=new CachedCatalog(new CliCatalog(List.of(root)));
    assertEquals(List.of("/session"),cache.complete(List.of("/s"),0,"/s"));
    assertEquals(List.of("resume"),cache.complete(List.of("/session","r"),1,"r"));
    assertEquals(List.of("--mode"),cache.complete(List.of("/session","resume","--m"),2,"--m"));
    assertEquals(List.of("READ_ONLY"),cache.complete(List.of("/session","switch","--mode","R"),3,"R"));
    assertTrue(cache.help(List.of("session","resume")).contains("--mode"));
  }
}
