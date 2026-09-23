package dev.mikoto2000.rei.activity;

import java.util.List;

/** Roles are uncertain projections of visible candidates, never measurements of engagement. */
public record ActivityRoles(ActivityRecord.Activity primary, List<ActivityRecord.Activity> secondary,
    List<ActivityRecord.Activity> background, double confidence, List<String> basis) {
  public ActivityRoles {
    secondary=List.copyOf(secondary); background=List.copyOf(background); basis=List.copyOf(basis);
  }
}
