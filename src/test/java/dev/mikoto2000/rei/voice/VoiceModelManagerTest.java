package dev.mikoto2000.rei.voice;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class VoiceModelManagerTest {
  @TempDir Path root;
  final byte[] content="verified-model".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  VoiceModelManifest manifest() throws Exception {
    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    return new VoiceModelManifest("test-fixed",List.of(
      new VoiceModelManifest.Asset("models/encoder.onnx",URI.create("https://example.org/fixed/encoder"),content.length,hash,"MIT"),
      new VoiceModelManifest.Asset("models/decoder.onnx",URI.create("https://example.org/fixed/decoder"),content.length,hash,"MIT")));
  }
  VoiceAssetTransport copies(AtomicInteger calls) {
    return (asset,path,cancel,progress)->{cancel.check();calls.incrementAndGet();Files.write(path,content);progress.accept(content.length);};
  }
  VoiceModelManager manager(VoiceModelManifest manifest,VoiceAssetTransport transport) {
    return new VoiceModelManager(root,manifest,transport,(state)->{});
  }
  VoiceModelManager.State finish(VoiceModelManager manager) throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
    while(manager.busy()&&System.nanoTime()<deadline)Thread.sleep(5);
    assertThat(manager.busy()).isFalse();return manager.status().state();
  }
  @Test void noApprovalMeansNoDownloadOrDirectoryMutation() throws Exception {
    var calls=new AtomicInteger();var m=manifest();
    try(var manager=manager(m,copies(calls))) {
      assertThatThrownBy(()->manager.install(null)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(()->manager.install("different-version")).isInstanceOf(IllegalArgumentException.class);
      assertThat(calls).hasValue(0);assertThat(root.resolve("managed")).doesNotExist();
    }
  }
  @Test void activatesOnlyCompleteVerifiedBundleAndReusesItOfflineAfterRestart() throws Exception {
    var calls=new AtomicInteger();var m=manifest();
    try(var manager=manager(m,copies(calls))) {
      manager.install(m.id());assertThat(finish(manager)).isEqualTo(VoiceModelManager.State.READY);
      m.verify(manager.readyDirectory());assertThat(calls).hasValue(2);
    }
    try(var manager=manager(m,(a,p,c,progress)->{throw new AssertionError("offline reuse must never download");})) {
      m.verify(manager.readyDirectory());assertThat(manager.status().state()).isEqualTo(VoiceModelManager.State.READY);
      assertThat(manager.install(m.id())).isFalse();
    }
  }
  @Test void halfBundleIsNotVisibleAndCancelCannotActivateIt() throws Exception {
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();var m=manifest();
    try(var manager=manager(m,(a,p,c,progress)-> {
      copies(calls).download(a,p,c,progress);
      if(calls.get()==2){entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}c.check();}
    })) {
      manager.install(m.id());assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(manager::readyDirectory).isInstanceOf(IOException.class);
      assertThat(manager.cancel()).isTrue();release.countDown();
      assertThat(finish(manager)).isEqualTo(VoiceModelManager.State.CANCELLED);
      assertThat(root.resolve("managed").resolve(m.id())).doesNotExist();
      try(var stages=Files.list(root.resolve("staging"))){assertThat(stages.toList()).isEmpty();}
    } finally {release.countDown();}
  }
  @Test void corruptedOrMixedBundleNeverBecomesReady() throws Exception {
    var m=manifest();
    try(var manager=manager(m,(a,p,c,progress)->Files.write(p,"wrong-model".getBytes()))) {
      manager.install(m.id());assertThat(finish(manager)).isEqualTo(VoiceModelManager.State.FAILED);
      assertThatThrownBy(manager::readyDirectory).isInstanceOf(IOException.class);
    }
  }
  @Test void retriesTransientFailuresAtMostThreeTimesAndSupportsExplicitRetry() throws Exception {
    var calls=new AtomicInteger();var m=manifest();
    try(var manager=manager(m,(a,p,c,progress)->{calls.incrementAndGet();throw new IOException("unavailable");})) {
      manager.install(m.id());assertThat(finish(manager)).isEqualTo(VoiceModelManager.State.FAILED);
      assertThat(calls).hasValue(3);
      manager.install(m.id());assertThat(finish(manager)).isEqualTo(VoiceModelManager.State.FAILED);
      assertThat(calls).hasValue(6);
    }
  }
  @Test void transientFailureCanRecoverWithinBound() throws Exception {
    var attempts=new AtomicInteger();var successful=new AtomicInteger();var m=manifest();
    try(var manager=manager(m,(a,p,c,progress)-> {
      if(attempts.incrementAndGet()==1)throw new IOException("temporary");
      copies(successful).download(a,p,c,progress);
    })) {
      manager.install(m.id());assertThat(finish(manager)).isEqualTo(VoiceModelManager.State.READY);
      assertThat(attempts).hasValue(3);assertThat(successful).hasValue(2);
    }
  }
  @Test void verifiesCompleteManualPlacementAndDetectsLaterCorruption() throws Exception {
    var m=manifest();Files.createDirectories(root.resolve("models"));
    for(var asset:m.assets())Files.write(root.resolve(asset.path()),content);
    try(var manager=manager(m,(a,p,c,progress)->{throw new AssertionError();})) {
      assertThat(manager.readyDirectory()).isEqualTo(root);
      Files.write(root.resolve(m.assets().getFirst().path()),"corrupt".getBytes());
      assertThatThrownBy(manager::readyDirectory).isInstanceOf(IOException.class);
      assertThat(manager.status().state().name()).isEqualTo("CORRUPT");
    }
  }
  @Test void preservesCorruptOldVersionWhenRepairingWithoutExposingPartialFiles() throws Exception {
    var m=manifest();var old=root.resolve("managed").resolve(m.id());Files.createDirectories(old);
    Files.writeString(old.resolve("old-evidence"),"preserve");
    try(var manager=manager(m,copies(new AtomicInteger()))) {
      manager.install(m.id());assertThat(finish(manager)).isEqualTo(VoiceModelManager.State.READY);
      m.verify(manager.readyDirectory());
      try(var retained=Files.walk(root.resolve("retained"))) {
        assertThat(retained.filter(p->p.getFileName().toString().equals("old-evidence")).count()).isEqualTo(1);
      }
    }
  }
  @Test void manifestRejectsTraversalInsecureUrlsAndDuplicateAssets() throws Exception {
    var good=manifest().assets().getFirst();
    for(String path:List.of("../escape","/absolute","models/../../escape","C:/escape","models\\escape")) {
      assertThatThrownBy(()->new VoiceModelManifest.Asset(path,good.url(),good.bytes(),good.sha256(),good.license())).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(()->new VoiceModelManifest.Asset("safe",URI.create("http://example.org/file"),1,good.sha256(),"MIT")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new VoiceModelManifest("../bad",List.of(good))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new VoiceModelManifest("duplicate",List.of(good,good))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void pinnedTurboFp32ManifestIncludesExternalWeightsAndImmutableRevision() {
    var m=VoiceModelManifest.pinned();
    assertThat(m.id()).isEqualTo("sherpa-1_13_8-whisper-turbo-fp32-2ca6ff69-silero-9e2449e1");
    assertThat(m.assets()).hasSize(7);assertThat(m.totalBytes()).isEqualTo(3247195692L);
    assertThat(m.assets().stream().filter(a->a.path().contains("turbo-")).map(a->a.url().toString()))
      .allMatch(u->u.contains("2ca6ff69fc878651b770880507669577ac41c2ff"));
    assertThat(m.assets().stream().map(VoiceModelManifest.Asset::path)).contains(
      "models/turbo-encoder.onnx","models/turbo-encoder.weights","models/turbo-decoder.onnx","models/turbo-tokens.txt")
      .noneMatch(p->p.contains("int8")||p.contains("base-"));
    for(int i=0;i<m.assets().size();i++) {
      var a=m.assets().get(i);var prior=SherpaBackendFactory.ASSETS.get(i);
      assertThat(a.path()).isEqualTo(prior.path());assertThat(a.bytes()).isEqualTo(prior.bytes());assertThat(a.sha256()).isEqualTo(prior.sha256());
    }
  }
  @Test void largeAssetBoundsRemainFiniteAndTotalsDoNotOverflowInt() throws Exception {
    var a=manifest().assets().getFirst();
    var big=new VoiceModelManifest.Asset("weights",a.url(),2600325120L,a.sha256(),a.license());
    assertThat(new VoiceModelManifest("large",List.of(big)).totalBytes()).isEqualTo(2600325120L);
    assertThatThrownBy(()->new VoiceModelManifest.Asset("oversize",a.url(),3L*1024*1024*1024+1,a.sha256(),a.license()))
      .isInstanceOf(IllegalArgumentException.class);
    var second=new VoiceModelManifest.Asset("second",a.url(),big.bytes(),a.sha256(),a.license());
    assertThatThrownBy(()->new VoiceModelManifest("too-large",List.of(big,second))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void newVersionKeepsOldManagedBundleAndRequiresFreshApproval() throws Exception {
    var old=manifest();Path oldDirectory;
    try(var manager=manager(old,copies(new AtomicInteger()))) {
      manager.install(old.id());assertThat(finish(manager)).isEqualTo(VoiceModelManager.State.READY);oldDirectory=manager.readyDirectory();
    }
    var next=new VoiceModelManifest("next-fp32",old.assets());var calls=new AtomicInteger();
    try(var manager=manager(next,copies(calls))) {
      assertThatThrownBy(manager::readyDirectory).isInstanceOf(IOException.class);
      assertThatThrownBy(()->manager.install(old.id())).isInstanceOf(IllegalArgumentException.class);
      assertThat(calls).hasValue(0);manager.install(next.id());assertThat(finish(manager)).isEqualTo(VoiceModelManager.State.READY);
      assertThat(manager.readyDirectory()).isNotEqualTo(oldDirectory);old.verify(oldDirectory);
    }
  }
  @Test void insufficientDiskFailsBeforeTransferAndNeverActivates() throws Exception {
    var m=manifest();var calls=new AtomicInteger();
    try(var manager=new VoiceModelManager(root,m,copies(calls),status->{},path->m.totalBytes()+VoiceModelManager.DISK_RESERVE_BYTES-1)) {
      manager.install(m.id());assertThat(finish(manager)).isEqualTo(VoiceModelManager.State.FAILED);
      assertThat(calls).hasValue(0);assertThat(manager.status().failure()).contains("insufficient free space");
      assertThat(root.resolve("managed").resolve(m.id())).doesNotExist();
      try(var stages=Files.list(root.resolve("staging"))){assertThat(stages.toList()).isEmpty();}
    }
  }
  @Test void externalWeightsAreRequiredForReadyAndOfflineReuse() throws Exception {
    var a=manifest().assets().getFirst();var weights=new VoiceModelManifest.Asset("models/encoder.weights",a.url(),a.bytes(),a.sha256(),a.license());
    var m=new VoiceModelManifest("with-sidecar",List.of(a,weights));
    try(var manager=manager(m,copies(new AtomicInteger()))) {
      manager.install(m.id());assertThat(finish(manager)).isEqualTo(VoiceModelManager.State.READY);
      var ready=manager.readyDirectory();Files.delete(ready.resolve(weights.path()));
      assertThatThrownBy(manager::readyDirectory).isInstanceOf(IOException.class);
      assertThat(manager.status().state()).isEqualTo(VoiceModelManager.State.CORRUPT);
    }
  }
  @Test void interruptedReuseCheckDoesNotStartANewDownload() throws Exception {
    var m=manifest();var calls=new AtomicInteger();
    try(var manager=manager(m,copies(calls))) {
      Thread.currentThread().interrupt();
      try{assertThatThrownBy(()->manager.install(m.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");}
      finally{Thread.interrupted();}
      assertThat(calls).hasValue(0);assertThat(manager.busy()).isFalse();assertThat(root.resolve("staging")).doesNotExist();
    }
  }
  @Test void integrityHashHonorsCancellationBeforeReading() throws Exception {
    var m=manifest();Path file=root.resolve("valid");Files.write(file,content);
    Thread.currentThread().interrupt();
    try{assertThatThrownBy(()->VoiceModelManifest.verifyAsset(m.assets().getFirst(),file)).isInstanceOf(InterruptedIOException.class);}
    finally{Thread.interrupted();}
  }
}
