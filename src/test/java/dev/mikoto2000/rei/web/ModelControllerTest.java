package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.service.*;
class ModelControllerTest {
  @Test void listAndSelectionReuseShellModelServicesAndSkipUnloadingUnchangedModel() {
    var models=mock(OpenAiCompatibleModelsService.class);
    var holder=mock(ModelHolderService.class);
    var unload=mock(OpenAiCompatibleModelUnloadService.class);
    when(models.listModels()).thenReturn(java.util.List.of("one","two"));when(holder.get()).thenReturn("one");
    var controller=new ModelController(models,holder,unload);
    assertEquals(java.util.List.of("one","two"),controller.list().models());
    assertEquals("one",controller.current().model());
    controller.select(new ModelController.Selection("one"));verifyNoInteractions(unload);
    controller.select(new ModelController.Selection("two"));verify(unload).unload("one");verify(holder).set("two");
  }
  @Test void unloadFailureStillAllowsSelectionAsInShellAndInvalidModelsNeverReachServices() {
    var models=mock(OpenAiCompatibleModelsService.class);var holder=mock(ModelHolderService.class);var unload=mock(OpenAiCompatibleModelUnloadService.class);
    when(holder.get()).thenReturn("one");doThrow(new IllegalStateException("fixture")).when(unload).unload("one");
    new ModelController(models,holder,unload).select(new ModelController.Selection("two"));verify(holder).set("two");
    for(String value:new String[]{""," ","a\nb","x".repeat(1025)})assertThrows(IllegalArgumentException.class,()->new ModelController.Selection(value));
  }
}
