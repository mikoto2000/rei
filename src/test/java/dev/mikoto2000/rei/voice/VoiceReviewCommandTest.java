package dev.mikoto2000.rei.voice;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import picocli.CommandLine;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.project.ProjectContext;
class VoiceReviewCommandTest {
  final VoiceInputCoordinator voice=mock(VoiceInputCoordinator.class);
  final ShellConversationService shell=mock(ShellConversationService.class);
  final VoiceDeliveryService delivery=mock(VoiceDeliveryService.class);
  final VoiceProperties properties=new VoiceProperties();
  final AudioDeviceService devices=new AudioDeviceService(()->List.of(new AudioDevice("dry","DRY (VT-4)","input","vendor","1")));
  CommandLine command(){var command=new CommandLine(new VoiceCommand(voice,devices,properties,shell,null,delivery));
    command.setOut(new PrintWriter(new StringWriter()));command.setErr(new PrintWriter(new StringWriter()));return command;}
  @Test void confirmationChangeValidatesSettingsAtomicallyAndRequiresStoppedCapture() {
    when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);var command=command();
    assertThat(command.execute("config","--confirmation","true","--silence-ms","800")).isZero();
    assertThat(properties.isConfirmation()).isTrue();verify(delivery).setConfirmation(true);
    assertThat(command.execute("config","--confirmation","false","--max-speech-ms","26000")).isEqualTo(2);
    assertThat(properties.isConfirmation()).isTrue();verify(delivery,never()).setConfirmation(false);
    when(voice.state()).thenReturn(VoiceInputCoordinator.State.LISTENING);
    assertThat(command.execute("config","--confirmation","false")).isEqualTo(2);assertThat(properties.isConfirmation()).isTrue();
  }
  @Test void explicitConfirmationAndCancellationUseRecognitionIdentityRatherThanToolApproval() {
    var command=command();var id=UUID.randomUUID();
    assertThat(command.execute("confirm",id.toString(),"--text","音声入力")).isZero();verify(delivery).confirm(id,"音声入力");
    when(delivery.cancel(id)).thenReturn(true);
    assertThat(command.execute("pending","cancel",id.toString())).isZero();verify(delivery).cancel(id);verifyNoInteractions(shell);
    assertThat(command.execute("off")).isZero();verify(delivery).clear();
  }
  @Test void startingCaptureBindsCurrentClientAndConfirmationBeforeOpeningMicrophone() {
    devices.select("dry");when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);
    when(voice.awaitStartup(VoiceRuntimeLimits.COMMAND_STARTUP)).thenReturn(VoiceInputCoordinator.State.LISTENING);
    var target=new ConversationTarget(new ProjectContext(UUID.randomUUID().toString(),"test",Path.of(".")),"session");
    when(shell.captureTarget()).thenReturn(target);properties.setConfirmation(true);
    assertThat(command().execute("on")).isZero();
    var order=inOrder(delivery,voice);order.verify(voice).state();order.verify(delivery).setConfirmation(true);
    order.verify(delivery).bind(null,target);order.verify(voice).start(target,devices.selected(),properties.settings());
  }
}
