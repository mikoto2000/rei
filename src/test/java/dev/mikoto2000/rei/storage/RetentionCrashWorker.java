package dev.mikoto2000.rei.storage;

public final class RetentionCrashWorker {
  public static void main(String[] args)throws Exception {
    var root=java.nio.file.Path.of(args[0]);
    try(var gate=new StorageMigrationCoordinator(root)) {
      gate.prepare();var registry=new StorageObjectRegistry(root,java.time.Clock.fixed(java.time.Instant.parse(args[4]),java.time.ZoneOffset.UTC));
      var executor=new RetentionExecutor(registry,new RetentionPlanner(registry),(stage,id)->{
        if(stage.equals(args[3])){System.out.println("PAUSED");System.out.flush();System.in.read();}
      });
      switch(args[1]){case "apply"->executor.apply(args[2]);case "restore"->executor.restore(args[2]);case "purge"->executor.purge(args[2]);default->throw new IllegalArgumentException("Unknown fixture action");}
    }
  }
}
