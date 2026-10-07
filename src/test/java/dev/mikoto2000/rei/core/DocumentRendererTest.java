package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DocumentRendererTest {
  @TempDir Path root;String project=UUID.randomUUID().toString();
  AgentRunContext owner(){return new AgentRunContext("run","session",root,project);}
  DriverManagerDataSource source(){return new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("renderer.db"));}
  DocumentRendererService service(DocumentRendererService.Renderer renderer){return new DocumentRendererService(source(),Clock.systemUTC(),true,renderer,null);}
  DocumentRendererService.Request request()throws Exception{String text="@startuml\nAlice -> Bob : hello\n@enduml\n";Files.writeString(root.resolve("diagram.puml"),text);return new DocumentRendererService.Request("diagram.puml",TextDocumentTransaction.hash(text));}
  byte[] png()throws Exception{var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);return output.toByteArray();}
  @Test void validImageRecordsActualSizeHashAndDuplicateDoesNotExecuteAgain()throws Exception{
    var count=new AtomicInteger();byte[] png=png();var service=service(text->{count.incrementAndGet();return new DocumentRendererService.Rendered(0,false,png,"fixture-renderer");});var request=request();var result=service.validate(owner(),request);
    assertEquals("RENDERED",result.status());assertEquals(png.length,result.size());assertEquals(64,result.outputSha256().length());assertNull(result.artifact());assertTrue(result.warnings().contains("ARTIFACT_DELIVERY_DISABLED"));assertEquals(result,service.validate(owner(),request));assertEquals(1,count.get());
    assertThrows(IllegalArgumentException.class,()->service.inspect(new AgentRunContext("foreign","other",root,project),result.id()));
  }
  @Test void exitOrParseErrorAndMissingImageCannotBeReportedAsRendered()throws Exception{
    for(var rendered:List.of(new DocumentRendererService.Rendered(200,true,new byte[0],"fixture"),new DocumentRendererService.Rendered(0,true,png(),"fixture"),new DocumentRendererService.Rendered(0,false,new byte[0],"fixture"))){var result=service(text->rendered).validate(new AgentRunContext(UUID.randomUUID().toString(),"session",root,project),request());assertEquals("INVALID",result.status());assertNull(result.outputSha256());}
  }
  @Test void disabledStaleUnsafeInputAndReadOnlyDoNotLaunchRenderer()throws Exception{
    var calls=new AtomicInteger();DocumentRendererService.Renderer renderer=text->{calls.incrementAndGet();throw new AssertionError();};var request=request();
    assertThrows(IllegalStateException.class,()->new DocumentRendererService(source(),Clock.systemUTC(),false,renderer,null).validate(owner(),request));var service=service(renderer);
    assertThrows(IllegalArgumentException.class,()->service.validate(new AgentRunContext("read","session",root,project,AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.READ_ONLY),request));Files.writeString(root.resolve("diagram.puml"),"changed");assertThrows(IllegalArgumentException.class,()->service.validate(owner(),request));
    for(String unsafe:List.of("@startuml\n!include secret.txt\n@enduml","@startuml\nAlice -> Bob : %load_json(\"https://example.org\")\n@enduml","@startuml\nAlice -> Bob\n@enduml\n@startuml\nAlice -> Bob\n@enduml")){Files.writeString(root.resolve("diagram.puml"),unsafe);assertThrows(IllegalArgumentException.class,()->service.validate(owner(),new DocumentRendererService.Request("diagram.puml",TextDocumentTransaction.hash(unsafe))));}assertEquals(0,calls.get());
  }
  @Test void timeoutCancellationAndFatalStopLeaveHonestDurableReceipts()throws Exception{
    var request=request();var timeout=service(text->{throw new java.util.concurrent.TimeoutException();});assertEquals("TIMEOUT",timeout.validate(owner(),request).status());
    var cancelled=service(text->{throw new java.util.concurrent.CancellationException();});var other=new AgentRunContext("cancel","session",root,project);assertThrows(java.util.concurrent.CancellationException.class,()->cancelled.validate(other,request));
    var fatal=service(text->{throw new AssertionError("fatal");});var stopped=new AgentRunContext("fatal","session",root,project);assertThrows(AssertionError.class,()->fatal.validate(stopped,request));
    var restarted=service(text->{throw new AssertionError("no replay");});assertEquals("UNKNOWN",restarted.validate(stopped,request).status());assertEquals("UNKNOWN",restarted.validate(other,request).status());
  }
  @Test void resourceMarkupCannotLaunchAnotherRendererOrFetchFiles()throws Exception{
    assertThrows(IllegalArgumentException.class,()->DocumentRendererService.safeInput("@startuml\nnote over Alice : <latex>resource</latex>\n@enduml"));
  }
  @Test void sourceDriftDuringRenderCannotPublishValidatedCurrentArtifact()throws Exception{
    var request=request();byte[] png=png();var service=service(text->{Files.writeString(root.resolve("diagram.puml"),"human changed");return new DocumentRendererService.Rendered(0,false,png,"fixture");});
    var result=service.validate(owner(),request);assertEquals("STALE",result.status());assertNull(result.artifact());assertNull(result.outputSha256());assertEquals("human changed",Files.readString(root.resolve("diagram.puml")));
  }
  @Test void existingToolUsesCapturedOwnerAndIntrinsicExecutionPermissions()throws Exception{
    var request=request();var tools=new Tools();byte[] png=png();tools.setDocumentRenderer(service(text->new DocumentRendererService.Rendered(0,false,png,"fixture")));
    assertThrows(IllegalArgumentException.class,()->tools.validateDocumentRender(request));try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner())){var result=tools.validateDocumentRender(request);assertEquals(result,tools.inspectDocumentRender(result.id()));}
    assertEquals(Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),dev.mikoto2000.rei.core.policy.ToolPermissionPolicy.intrinsicCapabilities("inspectDocumentRender"));assertEquals(Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ,dev.mikoto2000.rei.core.policy.ActionCapability.EXECUTE,dev.mikoto2000.rei.core.policy.ActionCapability.LOCAL_WRITE),dev.mikoto2000.rei.core.policy.ToolPermissionPolicy.intrinsicCapabilities("validateDocumentRender"));
  }
}
