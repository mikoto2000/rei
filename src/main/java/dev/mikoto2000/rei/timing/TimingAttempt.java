package dev.mikoto2000.rei.timing;
/** Reactor-context observation only; never changes prompts or request options. */
public record TimingAttempt(String requestId,int number,java.util.concurrent.atomic.AtomicReference<String> physicalSpan) {
 public static final Class<TimingAttempt> KEY=TimingAttempt.class;
 public TimingAttempt next(){return new TimingAttempt(requestId,number+1,new java.util.concurrent.atomic.AtomicReference<>());}
}
