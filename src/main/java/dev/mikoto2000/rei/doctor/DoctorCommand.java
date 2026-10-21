package dev.mikoto2000.rei.doctor;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;

/** Passive環境診断、または明示したActive診断を行うための利用者コマンド。 */
@Component
@Command(
    name = "doctor",
    description = "Passive環境診断、または明示したActive診断",
    mixinStandardHelpOptions = true)
public class DoctorCommand implements java.util.concurrent.Callable<Integer> {
  /** 環境診断。 */
  private final DoctorService doctor;

  /** 会話一覧。 */
  private final dev.mikoto2000.rei.application.session.ShellConversationService conversations;

  /** 引数。 */
  @Unmatched String[] arguments;

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 実行環境の診断を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public DoctorCommand() {
    this(null, null);
  }

  /** 実行環境の診断を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public DoctorCommand(DoctorService doctor) {
    this(doctor, null);
  }

  /** 指定された値や利用先を使って、DoctorCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public DoctorCommand(
      DoctorService doctor,
      dev.mikoto2000.rei.application.session.ShellConversationService conversations) {
    this.doctor = doctor;
    this.conversations = conversations;
  }

  /** Passive環境診断、または明示したActive診断を行う。 */
  public Integer call() {
    String operation = "引数の検証";
    try {
      var args = arguments == null ? new String[0] : arguments;
      var request = DoctorRequest.parse(args);
      if (request.checks().isEmpty()) {
        operation = "受動診断";
        if (doctor == null) throw new IllegalArgumentException("Doctor runtime unavailable");
        spec.commandLine()
            .getOut()
            .println(DoctorService.render(doctor.passive(request.details())));
      } else {
        operation = "診断Runの受付";
        if (conversations == null)
          throw new IllegalArgumentException("Doctor Run queue unavailable");
        spec.commandLine().getOut().println(request.plan());
        spec.commandLine().getOut().flush();
        conversations.submit(
            "/doctor "
                + java.util.Arrays.stream(args)
                    .map(
                        a ->
                            dev.mikoto2000.rei.core.command.UserInputParser.quote(
                                a, true, (char) 0))
                    .collect(java.util.stream.Collectors.joining(" ")));
      }
      return 0;
    } catch (RuntimeException failure) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(failure);
      spec.commandLine()
          .getErr()
          .println(
              "Doctor diagnosis unavailable or invalid arguments. Use /doctor [--details] [--check connectivity|inference|codex|claude|microphone]. "
                  + "原因: "
                  + operation
                  + "に失敗しました ("
                  + failure.getClass().getSimpleName()
                  + ")。設定を /doctor --details で確認してください。");
      return 2;
    }
  }
}
