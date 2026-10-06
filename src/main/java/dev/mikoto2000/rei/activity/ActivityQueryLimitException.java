package dev.mikoto2000.rei.activity;
/** No partial statistics are published after exceeding the query's evidence budget. */
public class ActivityQueryLimitException extends RuntimeException {
  public ActivityQueryLimitException(){super("Activity analysis evidence limit exceeded");}
}
