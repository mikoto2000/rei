package dev.mikoto2000.rei.doctor;

import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

@Component
@Command(name="doctor", description="現在の実効設定とファイルのPassive診断", mixinStandardHelpOptions=true)
public class DoctorCommand implements java.util.concurrent.Callable<Integer> {
  private final DoctorService doctor;
  @Option(names="--details") boolean details;
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  public DoctorCommand() {this(null);}
  @org.springframework.beans.factory.annotation.Autowired
  public DoctorCommand(DoctorService doctor) {this.doctor=doctor;}
  public Integer call() {
    if (doctor == null) {spec.commandLine().getErr().println("Doctor runtime unavailable"); return 2;}
    try {
      spec.commandLine().getOut().println(DoctorService.render(doctor.passive(details)));
      return 0;
    } catch (RuntimeException error) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
      spec.commandLine().getErr().println("Passive diagnosis unavailable; exception details omitted");
      return 2;
    }
  }
}
