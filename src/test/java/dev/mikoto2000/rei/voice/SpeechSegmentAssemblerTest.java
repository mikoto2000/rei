package dev.mikoto2000.rei.voice;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SpeechSegmentAssemblerTest {
  final Clock clock=Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"),ZoneOffset.UTC);
  float[] frame(float amplitude){var frame=new float[160];Arrays.fill(frame,amplitude);return frame;}
  SpeechSegmentAssembler assembler(){return new SpeechSegmentAssembler(new VoiceSettings(.5f,300,400,1200,25000,200),clock);}
  @Test void silenceIsFinalizedOnceAfter1200msAndRetainsPrerollAnd200msTail() {
    var assembler=assembler();
    for(int i=0;i<40;i++)assembler.accept(frame(.1f),0);
    for(int i=0;i<50;i++)assertThat(assembler.accept(frame(.6f),1).segment()).isNull();
    for(int i=0;i<119;i++)assertThat(assembler.accept(frame(.01f),0).segment()).isNull();
    var decision=assembler.accept(frame(.01f),0);
    assertThat(decision.reason()).isEqualTo(SpeechSegmentAssembler.Reason.COMPLETED);
    var segment=decision.segment();
    assertThat(segment.samples()).hasSize((300+500+200)*16);
    assertThat(segment.samples()[0]).isEqualTo(.1f);
    assertThat(segment.samples()[4800]).isEqualTo(.6f);
    assertThat(segment.samples()[segment.samples().length-1]).isEqualTo(.01f);
    assertThat(segment.createdAt()).isEqualTo(clock.instant());
    for(int i=0;i<200;i++)assertThat(assembler.accept(frame(0),0).segment()).isNull();
  }
  @Test void shortNoiseNeverProducesASegment() {
    var assembler=assembler();
    for(int i=0;i<39;i++)assembler.accept(frame(.8f),1);
    SpeechSegmentAssembler.Decision last=null;
    for(int i=0;i<120;i++)last=assembler.accept(frame(0),0);
    assertThat(last.reason()).isEqualTo(SpeechSegmentAssembler.Reason.SHORT_DROPPED);
    assertThat(last.segment()).isNull();
  }
  @Test void unfinished25SecondSpeechIsDroppedAndCannotSendItsContinuation() {
    var assembler=assembler();
    SpeechSegmentAssembler.Decision last=null;
    for(int i=0;i<2500;i++)last=assembler.accept(frame(.5f),1);
    assertThat(last.reason()).isEqualTo(SpeechSegmentAssembler.Reason.MAX_DROPPED);
    assertThat(last.segment()).isNull();
    for(int i=0;i<3000;i++)assertThat(assembler.accept(frame(.5f),1).segment()).isNull();
    for(int i=0;i<120;i++)assembler.accept(frame(0),0);
    for(int i=0;i<50;i++)assembler.accept(frame(.4f),1);
    for(int i=0;i<120;i++)last=assembler.accept(frame(0),0);
    assertThat(last.reason()).isEqualTo(SpeechSegmentAssembler.Reason.COMPLETED);
  }
  @Test void briefPauseDoesNotEndOrCreateAnotherUtterance() {
    var assembler=assembler();
    for(int i=0;i<50;i++)assembler.accept(frame(.4f),1);
    for(int i=0;i<100;i++)assertThat(assembler.accept(frame(0),0).segment()).isNull();
    for(int i=0;i<50;i++)assembler.accept(frame(.4f),1);
    SpeechSegmentAssembler.Decision last=null;
    for(int i=0;i<120;i++)last=assembler.accept(frame(0),0);
    assertThat(last.segment().samples()).hasSize((500+1000+500+200)*16);
  }
  @Test void stopDiscardsUnfinishedAudioWithoutFlushingToAsr() {
    var assembler=assembler();
    for(int i=0;i<60;i++)assembler.accept(frame(.8f),1);
    assembler.reset();
    for(int i=0;i<200;i++)assertThat(assembler.accept(frame(0),0).segment()).isNull();
  }
  @Test void assemblerRejectsInvalidFramesAndProbability() {
    var assembler=assembler();
    assertThatThrownBy(()->assembler.accept(new float[0],1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->assembler.accept(frame(.1f),Float.NaN)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->assembler.accept(new float[513],1)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void segmentsOwnTheirSamplesAndDistinctIds() {
    var first=assembler();var second=assembler();
    SpeechSegmentAssembler.Decision a=null,b=null;
    for(int i=0;i<50;i++){first.accept(frame(.5f),1);second.accept(frame(.5f),1);}
    for(int i=0;i<120;i++){a=first.accept(frame(0),0);b=second.accept(frame(0),0);}
    assertThat(a.segment().id()).isNotEqualTo(b.segment().id());
    var copy=a.segment().samples();copy[0]=0;
    assertThat(a.segment().samples()[0]).isEqualTo(.5f);
  }
}