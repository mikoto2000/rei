package dev.mikoto2000.rei.web;
public record ReminderResponse(long id,String message,String type,String remindAt,String targetAt,Integer minutesBefore,boolean notified) {
  public static ReminderResponse from(dev.mikoto2000.rei.reminder.Reminder r) { return new ReminderResponse(r.id(),r.message(),r.type().name(),r.remindAt().toString(),r.targetAt()==null?null:r.targetAt().toString(),r.minutesBefore(),r.notified()); }
}
