package dev.mikoto2000.rei.doctor;

import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

@Component
@Command(name="doctor",description="Passive環境診断、または明示したActive診断",mixinStandardHelpOptions=true)
public class DoctorCommand implements java.util.concurrent.Callable<Integer> {
  private final DoctorService doctor;
  private final dev.mikoto2000.rei.application.session.ShellConversationService conversations;
  @Unmatched String[] arguments;
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  public DoctorCommand(){this(null,null);}
  public DoctorCommand(DoctorService doctor){this(doctor,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public DoctorCommand(DoctorService doctor,dev.mikoto2000.rei.application.session.ShellConversationService conversations) {
    this.doctor=doctor;this.conversations=conversations;
  }
  public Integer call() {
    try {
      var args=arguments==null?new String[0]:arguments;
      var request=DoctorRequest.parse(args);
      if(request.checks().isEmpty()) {
        if(doctor==null)throw new IllegalArgumentException("Doctor runtime unavailable");
        spec.commandLine().getOut().println(DoctorService.render(doctor.passive(request.details())));
      }else {
        if(conversations==null)throw new IllegalArgumentException("Doctor Run queue unavailable");
        spec.commandLine().getOut().println(request.plan());
        spec.commandLine().getOut().flush();
        conversations.submit("/doctor "+java.util.Arrays.stream(args)
            .map(a->dev.mikoto2000.rei.core.command.UserInputParser.quote(a,true,(char)0))
            .collect(java.util.stream.Collectors.joining(" ")));
      }
      return 0;
    }catch(RuntimeException failure) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(failure);
      spec.commandLine().getErr().println("Doctor diagnosis unavailable or invalid arguments. Use /doctor [--details] [--check connectivity|inference|codex|claude|microphone]. Details omitted.");
      return 2;
    }
  }
}
