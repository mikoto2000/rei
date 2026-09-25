package dev.mikoto2000.rei.activity;

/** Optional per-scope outcome. Legacy visionUsed means attempted, not accepted evidence. */
public record VisionDiagnostics(Result foreground,Result background) {
  public enum State { NOT_ATTEMPTED, ATTEMPTED, ATTEMPTED_FAILED, ATTEMPTED_SUCCEEDED_NOT_USED, USED, UNKNOWN }
  public record Result(State state,ActivityVisionFailure failure) {}
  public static VisionDiagnostics initial() {
    return new VisionDiagnostics(new Result(State.NOT_ATTEMPTED,null),new Result(State.NOT_ATTEMPTED,null));
  }
  public VisionDiagnostics with(boolean backgroundScope,State state,ActivityVisionFailure failure) {
    return backgroundScope?new VisionDiagnostics(foreground,new Result(state,failure)):new VisionDiagnostics(new Result(state,failure),background);
  }
  public static VisionDiagnostics of(ActivityRecord.Detection d) {
    if(d!=null && d.visionDiagnostics()!=null)return d.visionDiagnostics();
    // Old sources and visionUsed included failed and rejected results. Never infer USED.
    return new VisionDiagnostics(legacy(d,"VISION_FOREGROUND"),legacy(d,"VISION_BACKGROUND"));
  }
  private static Result legacy(ActivityRecord.Detection d,String scope) {
    if(d==null || "LEGACY".equals(d.classificationMode()))return new Result(State.UNKNOWN,null);
    if(!d.classificationSources().contains(scope))return new Result(d.visionUsed()?State.UNKNOWN:State.NOT_ATTEMPTED,null);
    return new Result(State.UNKNOWN,null);
  }
}
