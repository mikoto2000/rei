package dev.mikoto2000.rei.slack;
/** Only fixed diagnostics may cross the connection initialization boundary. */
final class SlackSetupException extends RuntimeException {
  enum Code {ANOTHER_BACKEND,WORKSPACE_NOT_ALLOWED,AUTHENTICATION_FAILED,USER_VALIDATION_FAILED,NO_HUMAN_USERS,PROJECT_NOT_REGISTERED,CONNECTION_FAILED}
  final Code code;
  SlackSetupException(Code code){super(code.name());this.code=code;}
}
