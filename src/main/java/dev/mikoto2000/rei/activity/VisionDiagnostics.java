package dev.mikoto2000.rei.activity;

/** Optional per-scope outcome. Legacy visionUsed means attempted, not accepted evidence. */
public record VisionDiagnostics(Result foreground,Result background) {
  public enum State { NOT_ATTEMPTED, ATTEMPTED, ATTEMPTED_FAILED, ATTEMPTED_SUCCEEDED_NOT_USED, USED, UNKNOWN }
  public record Timing(String observationId,java.time.Instant imageCapturedAt,java.time.Instant startedAt,java.time.Instant completedAt) {}
  public record Context(String observationId,java.time.Instant observedAt,java.util.List<ActivityRecord.Activity> candidates,double confidence) {
    public Context {candidates=java.util.List.copyOf(candidates);if(candidates.size()>3 || !Double.isFinite(confidence) || confidence<0 || confidence>1)throw new IllegalArgumentException("Invalid desktop context");}
  }
  public record Result(State state,ActivityVisionFailure failure,Timing timing,Context context) {
    public Result(State state,ActivityVisionFailure failure,Timing timing){this(state,failure,timing,null);}
    public Result(State state,ActivityVisionFailure failure){this(state,failure,null);}
  }
  public static VisionDiagnostics initial() {
    return new VisionDiagnostics(new Result(State.NOT_ATTEMPTED,null),new Result(State.NOT_ATTEMPTED,null));
  }
  public VisionDiagnostics with(boolean backgroundScope,State state,ActivityVisionFailure failure) {
    var prior=backgroundScope?background:foreground;
    var result=new Result(state,failure,prior==null?null:prior.timing(),prior==null?null:prior.context());
    return backgroundScope?new VisionDiagnostics(foreground,result):new VisionDiagnostics(result,background);
  }
  public VisionDiagnostics timing(boolean backgroundScope,Timing timing) {
    var prior=backgroundScope?background:foreground;
    var result=new Result(prior==null?State.UNKNOWN:prior.state(),prior==null?null:prior.failure(),timing,prior==null?null:prior.context());
    return backgroundScope?new VisionDiagnostics(foreground,result):new VisionDiagnostics(result,background);
  }
  public VisionDiagnostics context(Context context) {
    var prior=background;
    return new VisionDiagnostics(foreground,new Result(prior==null?State.UNKNOWN:prior.state(),prior==null?null:prior.failure(),prior==null?null:prior.timing(),context));
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
