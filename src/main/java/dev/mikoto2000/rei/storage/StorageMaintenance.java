package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.sql.*;
import java.time.*;

/** Observation is read-only. Space reclamation requires an explicit operation. */
public final class StorageMaintenance {
  public record Status(long databaseBytes,long walBytes,long temporaryBytes,long pageSize,long pageCount,long freePages,long estimatedUsedPageBytes,int autoVacuum,long usableDiskBytes) {}
  public record Checkpoint(int busy,long logFrames,long checkpointedFrames) {}
  private final StorageObjectRegistry registry;
  public StorageMaintenance(StorageObjectRegistry registry){this.registry=registry;}
  private long size(String name)throws Exception {var path=registry.root.resolve(name);StorageBackup.requireSafePath(path);return Files.exists(path,LinkOption.NOFOLLOW_LINKS)?Files.size(path):0;}
  private static long pragma(Connection db,String name)throws Exception {try(var query=db.createStatement();var rows=query.executeQuery("PRAGMA "+name)){rows.next();return rows.getLong(1);}}
  public Status status(){return registry.database.read(db->{long pageSize=pragma(db,"page_size"),pages=pragma(db,"page_count"),free=pragma(db,"freelist_count");return new Status(size("storage.db"),size("storage.db-wal"),size("storage.db-journal"),pageSize,pages,free,Math.multiplyExact(pages-free,pageSize),(int)pragma(db,"auto_vacuum"),Files.getFileStore(registry.root).getUsableSpace());});}
  private static Checkpoint checkpoint(Connection db)throws Exception {try(var query=db.createStatement();var rows=query.executeQuery("PRAGMA wal_checkpoint(PASSIVE)")){rows.next();return new Checkpoint(rows.getInt(1),rows.getLong(2),rows.getLong(3));}}
  public Checkpoint checkpoint(){return registry.database.maintenance(StorageMaintenance::checkpoint);}
  public boolean incrementalVacuum(int pages){if(pages<1||pages>10000)throw new IllegalArgumentException("Incremental vacuum requires 1..10,000 pages");return registry.database.maintenance(db->{if(pragma(db,"auto_vacuum")!=2)return false;try(var query=db.createStatement()){query.execute("PRAGMA incremental_vacuum("+pages+")");}return true;});}
  public void vacuum(Duration window){if(window==null||window.toSeconds()<1||window.toSeconds()>3600)throw new IllegalArgumentException("Explicit maintenance window must be 1..3600 seconds");
    registry.database.maintenance(db->{
      try(var query=db.createStatement();var rows=query.executeQuery("SELECT 1 FROM turns WHERE status='RUNNING' LIMIT 1")){if(rows.next())throw new IllegalStateException("Active Run; reschedule maintenance");}
      long required=Math.addExact(Math.addExact(Math.multiplyExact(size("storage.db"),2),size("storage.db-wal")),64L*1024*1024);if(Files.getFileStore(registry.root).getUsableSpace()<required)throw new IllegalStateException("Insufficient disk space for VACUUM");
      var checkpoint=checkpoint(db);if(checkpoint.busy()!=0||checkpoint.logFrames()>checkpoint.checkpointedFrames())throw new IllegalStateException("Reader prevents checkpoint; reschedule maintenance");
      long deadline=System.nanoTime()+window.toNanos();org.sqlite.ProgressHandler.setHandler(db,10000,new org.sqlite.ProgressHandler(){@Override protected int progress(){return Thread.currentThread().isInterrupted()||System.nanoTime()>=deadline?1:0;}});
      try(var query=db.createStatement()){query.execute("PRAGMA busy_timeout="+Math.min(5000,window.toMillis()));query.execute("VACUUM");}finally{org.sqlite.ProgressHandler.clearHandler(db);}return null;
    });
  }
}
