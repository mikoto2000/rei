package dev.mikoto2000.rei.temporal.holiday;

/** CSV上の休日の確実性と、確定できる場合の法的種別。 */
public enum HolidayStatus {
  HOLIDAY,
  SUBSTITUTE_HOLIDAY,
  CITIZENS_HOLIDAY,
  UNCLASSIFIED_DAY_OFF,
  NOT_HOLIDAY,
  UNKNOWN
}
