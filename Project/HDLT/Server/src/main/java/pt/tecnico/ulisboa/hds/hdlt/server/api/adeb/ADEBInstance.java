package pt.tecnico.ulisboa.hds.hdlt.server.api.adeb;

import com.google.protobuf.ByteString;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.UserReport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public class ADEBInstance {

  private final Map<UserReport, ADEBMetadata> metadataMap;
  private final Set<String> seenEchosNames;
  private final Set<String> seenReadiesNames;
  private final CountDownLatch countDownLatch;
  private boolean isProcessing;
  private Throwable throwable;

  public ADEBInstance() {
    this.isProcessing = false;
    this.metadataMap = new HashMap<>();
    this.seenEchosNames = new HashSet<>();
    this.seenReadiesNames = new HashSet<>();
    this.countDownLatch = new CountDownLatch(1);
  }

  public Throwable getThrowable() {
    return this.throwable;
  }

  public void setThrowable(Throwable throwable) {
    this.throwable = throwable;
  }

  public boolean addEcho(String sName, UserReport uReport) {
    if (this.seenEchosNames.add(sName)) {
      this.metadataMap.putIfAbsent(uReport, new ADEBMetadata(uReport.getUIdProofsMap().keySet()));
      this.metadataMap.get(uReport).addEcho();
      return true;
    }
    return false;
  }

  public boolean addReady(
      String sName, UserReport uReport, Map<String, Map<String, ByteString>> sIdProofs) {
    if (this.seenReadiesNames.add(sName)) {
      this.metadataMap.putIfAbsent(uReport, new ADEBMetadata(uReport.getUIdProofsMap().keySet()));
      this.metadataMap.get(uReport).addReady(sIdProofs);
      return true;
    }
    return false;
  }

  public ADEBMetadata getMetadata(UserReport uReport) {
    return this.metadataMap.get(uReport);
  }

  public boolean isProcessing() {
    return this.isProcessing;
  }

  public void process() {
    this.isProcessing = true;
  }

  public boolean hasSentReady(String sName) {
    return this.seenReadiesNames.contains(sName);
  }

  public boolean hasDelivered() {
    return countDownLatch.getCount() == 0;
  }

  public void deliver() {
    countDownLatch.countDown();
  }

  public void awaitDelivery() {
    try {
      this.countDownLatch.await();
    } catch (InterruptedException ignored) {
    }
  }

  public static class ADEBMetadata {

    private final Map<String, Map<String, ByteString>> sIdProofs;
    private Integer echoCounter;
    private Integer readyCounter;

    public ADEBMetadata(Set<String> uSigners) {
      this.echoCounter = 0;
      this.readyCounter = 0;
      this.sIdProofs =
          uSigners.stream()
              .collect(Collectors.toMap(uSigner -> uSigner, uSigner -> new HashMap<>()));
    }

    public Integer echoCounter() {
      return this.echoCounter;
    }

    public void addEcho() {
      this.echoCounter++;
    }

    public Integer readyCounter() {
      return this.readyCounter;
    }

    public void addReady(Map<String, Map<String, ByteString>> sIdProofs) {
      for (Map.Entry<String, Map<String, ByteString>> sIdProof : sIdProofs.entrySet()) {
        this.sIdProofs.get(sIdProof.getKey()).putAll(sIdProof.getValue());
      }
      this.readyCounter =
          this.sIdProofs.values().stream().map(Map::size).min(Integer::compare).orElse(0);
    }

    public Map<String, Map<String, ByteString>> getSIdProofs() {
      return this.sIdProofs;
    }
  }
}
