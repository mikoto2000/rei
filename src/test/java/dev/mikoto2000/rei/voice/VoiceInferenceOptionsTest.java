package dev.mikoto2000.rei.voice;
import java.io.*;import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
import picocli.CommandLine;import dev.mikoto2000.rei.application.session.ShellConversationService;
class VoiceInferenceOptionsTest {
  @Test void cpuBudgetAndPaddingAreBounded() {
    assertThat(VoiceInferenceOptions.defaults()).isEqualTo(new VoiceInferenceOptions(4,1000));
    for(int threads:new int[]{0,5})assertThatThrownBy(()->new VoiceInferenceOptions(threads,500)).isInstanceOf(IllegalArgumentException.class);
    for(int padding:new int[]{249,2001})assertThatThrownBy(()->new VoiceInferenceOptions(1,padding)).isInstanceOf(IllegalArgumentException.class);
  }
  CommandLine command(VoiceInputCoordinator voice,VoiceProperties properties){
    var cli=new CommandLine(new VoiceCommand(voice,new AudioDeviceService(()->java.util.List.of()),properties,mock(ShellConversationService.class)));
    cli.setOut(new PrintWriter(new StringWriter()));cli.setErr(new PrintWriter(new StringWriter()));return cli;
  }
  @Test void inferenceChangesAreOffOnlyAndVadEditsPreserveThem() {
    var voice=mock(VoiceInputCoordinator.class);when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);var properties=new VoiceProperties();var cli=command(voice,properties);
    assertThat(cli.execute("config","--asr-threads","2","--asr-tail-frames","1000")).isZero();
    assertThat(properties.inference()).isEqualTo(new VoiceInferenceOptions(2,1000));
    assertThat(cli.execute("config","--silence-ms","1200")).isZero();
    assertThat(properties.inference()).isEqualTo(new VoiceInferenceOptions(2,1000));
    when(voice.state()).thenReturn(VoiceInputCoordinator.State.STARTING);
    assertThat(cli.execute("config","--asr-threads","4")).isEqualTo(2);
    assertThat(properties.inference()).isEqualTo(new VoiceInferenceOptions(2,1000));verify(voice,never()).start(any(),any(),any());
  }
  @Test void invalidInferenceCannotPartiallyChangeVadOrConfirmation() {
    var voice=mock(VoiceInputCoordinator.class);when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);var properties=new VoiceProperties();var cli=command(voice,properties);
    assertThat(cli.execute("config","--asr-threads","5","--silence-ms","600","--confirmation","true")).isEqualTo(2);
    assertThat(properties.inference()).isEqualTo(VoiceInferenceOptions.defaults());assertThat(properties.settings()).isEqualTo(VoiceSettings.defaults());assertThat(properties.isConfirmation()).isFalse();
  }
  @Test void selectedSilencePreservesA1500msThinkingPauseAndFinalizesOnce() {
    var assembler=new SpeechSegmentAssembler(VoiceSettings.defaults(),Clock.systemUTC());float[] frame=new float[160];
    for(int i=0;i<50;i++)assembler.accept(frame,1);
    for(int i=0;i<150;i++)assertThat(assembler.accept(frame,0).segment()).isNull();
    for(int i=0;i<50;i++)assembler.accept(frame,1);
    for(int i=0;i<179;i++)assertThat(assembler.accept(frame,0).segment()).isNull();
    assertThat(assembler.accept(frame,0).segment().samples()).hasSize(2700*16);
    for(int i=0;i<200;i++)assertThat(assembler.accept(frame,0).segment()).isNull();
  }
}