package dev.mikoto2000.rei.checkpoint;
public class CheckpointException extends IllegalStateException {
  public enum Code { DISABLED, CAPACITY, TASK_BUSY, RECONCILIATION_REQUIRED, CORRUPT_CHECKPOINT, INCOMPATIBLE_SCHEMA }
  private final Code code;
  public CheckpointException(Code code,String message){super(message);this.code=code;}
  public Code code(){return code;}
}
