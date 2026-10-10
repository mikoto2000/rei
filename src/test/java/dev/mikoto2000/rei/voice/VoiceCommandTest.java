package dev.mikoto2000.rei.voice;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import picocli.CommandLine;
import dev.mikoto2000.rei.application.session.ShellConversationService;
class VoiceCommandTest {
  @Test void advancedFeaturesAreExplicitOffOnlyAndInvalidUpdatesAreAtomic() {
    var voice=mock(VoiceInputCoordinator.class);when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);
    var properties=new VoiceProperties();var command=new CommandLine(new VoiceCommand(voice,new AudioDeviceService(()->List.of()),properties,mock(ShellConversationService.class)));
    assertThat(command.execute("features","--wake","true","--wake-word","れい")).isZero();
    assertThat(properties.advanced().wakeEnabled()).isTrue();
    var before=properties.advanced();
    assertThat(command.execute("features","--tts","true","--echo-tail-ms","1")).isEqualTo(2);
    assertThat(properties.advanced()).isEqualTo(before);
    when(voice.state()).thenReturn(VoiceInputCoordinator.State.LISTENING);
    assertThat(command.execute("features","--wake","false")).isEqualTo(2);
    assertThat(properties.advanced()).isEqualTo(before);
    assertThat(command.execute("features")).isZero();
  }
  @Test void selectionIsExplicitAndConfigurationValidatesBeforeChangingSettings() {
    var devices=new AudioDeviceService(()->List.of(new AudioDevice("dry","DRY (VT-4)","input","vendor","1")));
    var voice=mock(VoiceInputCoordinator.class); when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);
    var shell=mock(ShellConversationService.class); var properties=new VoiceProperties();
    var output=new StringWriter(); var command=new CommandLine(new VoiceCommand(voice,devices,properties,shell));
    command.setOut(new PrintWriter(output,true)); command.setErr(new PrintWriter(output,true));
    assertThat(command.execute("on")).isEqualTo(2); verifyNoInteractions(shell);
    assertThat(command.execute("devices")).isZero(); assertThat(output.toString()).contains("DRY (VT-4)","dry");
    assertThat(command.execute("device","set","dry")).isZero();
    assertThat(command.execute("config","--silence-ms","800")).isZero();
    assertThat(properties.settings().silenceMs()).isEqualTo(800);
    assertThat(command.execute("config","--max-speech-ms","26000")).isEqualTo(2);
    assertThat(properties.settings().maxSpeechMs()).isEqualTo(25000);
  }
  @Test void microphoneSelectionAndConfigCannotChangeDuringCapture() {
    var devices=new AudioDeviceService(()->List.of(new AudioDevice("dry","DRY (VT-4)","input","vendor","1")));
    var voice=mock(VoiceInputCoordinator.class);when(voice.state()).thenReturn(VoiceInputCoordinator.State.LISTENING);
    var properties=new VoiceProperties();
    var command=new CommandLine(new VoiceCommand(voice,devices,properties,mock(ShellConversationService.class)));
    assertThat(command.execute("device","set","dry")).isEqualTo(2);
    assertThat(command.execute("config","--silence-ms","800")).isEqualTo(2);
    assertThat(properties.settings()).isEqualTo(VoiceSettings.defaults());
    assertThat(command.execute("off")).isZero();verify(voice).off();
  }

  @Test void successfulStartWaitsForInitializationBeforeReturningToPrompt() {
    var devices=new AudioDeviceService(()->List.of(new AudioDevice("dry","DRY (VT-4)","input","vendor","1")));
    devices.select("dry");
    var voice=mock(VoiceInputCoordinator.class);when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);
    var startup = mock(VoiceInputCoordinator.Startup.class);
    when(startup.await(VoiceRuntimeLimits.COMMAND_STARTUP)).thenReturn(VoiceInputCoordinator.State.LISTENING);
    when(voice.start(any(),any(),any())).thenReturn(startup);
    var shell=mock(ShellConversationService.class);
    var target=new dev.mikoto2000.rei.application.input.ConversationTarget(
      new dev.mikoto2000.rei.core.project.ProjectContext(java.util.UUID.randomUUID().toString(),"test",java.nio.file.Path.of(".")),"session");
    when(shell.captureTarget()).thenReturn(target);
    var command=new CommandLine(new VoiceCommand(voice,devices,new VoiceProperties(),shell));
    assertThat(command.execute("on")).isZero();
    var order=inOrder(voice,startup);order.verify(voice,times(2)).state();
    order.verify(voice).start(target,devices.selected(),VoiceSettings.defaults());
    order.verify(startup).await(VoiceRuntimeLimits.COMMAND_STARTUP);

  }
}