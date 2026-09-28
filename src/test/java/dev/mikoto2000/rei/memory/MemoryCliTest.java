package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import picocli.CommandLine;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.command.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;

class MemoryCliTest {
  @TempDir Path dir;
  @Test void manualSleepAndMemoryCommandsAreReachable() {
    var props=new MemoryProperties(true,20,80,10,3,2000,60,null);
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db"));
    var legacy=new MemoryService(ds,props);
    var repo=new MemoryRepository(ds,legacy);
    var projects=new ProjectService(dir,new ProjectRegistry(dir.resolve("projects.json")));
    var service=new SleepService(repo,ConversationTurnStore.inMemory(),t -> java.util.List.of(),new MemoryResolver(mock(MemoryResolutionModel.class),props),props);
    var support=new MemoryCommandSupport(repo,service,projects,props);
    try(var scope=ProjectClientScope.open(projects.newClient())) {
      projects.selectSession("s");
      var sleep=new CommandLine(new SleepCommand(service,support,projects,new CommandCancellationService()));
      var output=new StringWriter(); sleep.setOut(new PrintWriter(output,true));
      for(String arg:new String[]{"preview","status","history",""})
        assertEquals(0,arg.isEmpty()?sleep.execute():sleep.execute(arg),output.toString());
      assertTrue(output.toString().contains("unslept turns"));
      var memory=repo.insert(LongTermMemoryTest.candidate("Vision first",MemoryScope.GLOBAL),null,"s");
      var root=new MemoryCommand();
      var cli=new CommandLine(root,new CommandLine.IFactory() {
        public <K>K create(Class<K> cls) throws Exception {
          Object command;
          if(cls==MemoryCommand.ListCommand.class) { var c=new MemoryCommand.ListCommand(legacy); c.setSupport(support); command=c; }
          else if(cls==MemoryCommand.SearchCommand.class) { var c=new MemoryCommand.SearchCommand(legacy); c.setSupport(support); command=c; }
          else if(cls==MemoryCommand.ForgetCommand.class) { var c=new MemoryCommand.ForgetCommand(legacy); c.setSupport(support); command=c; }
          else if(cls==MemoryCommand.ShowCommand.class) command=new MemoryCommand.ShowCommand(support);
          else return mock(cls);
          return cls.cast(command);
        }
      });
      assertEquals(0,cli.execute("list"));
      assertEquals(0,cli.execute("search","Vision","first"));
      assertEquals(0,cli.execute("show",memory.id()));
      assertEquals(0,cli.execute("forget",memory.id()));
      assertEquals(MemoryStatus.ARCHIVED,repo.find(memory.id()).orElseThrow().status());
    }
  }
}
