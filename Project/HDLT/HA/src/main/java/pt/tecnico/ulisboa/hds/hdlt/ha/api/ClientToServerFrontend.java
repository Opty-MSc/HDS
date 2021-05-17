package pt.tecnico.ulisboa.hds.hdlt.ha.api;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesGrpc;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesGrpc.ClientServerServicesBlockingStub;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.*;
import pt.tecnico.ulisboa.hds.hdlt.ha.error.HARuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.ha.session.Session;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.lib.error.AssertError;

import javax.crypto.spec.IvParameterSpec;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.grpc.Status.Code.*;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.await;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.sleep;

public class ClientToServerFrontend {

  private final int callTimeout;
  private final int maxNRetries;
  private final String uname;
  private final HACrypto hCrypto;
  private final List<ManagedChannel> channels;
  private final Map<String, ClientServerServicesBlockingStub> stubs;
  private final HAToDHServerFrontend dhFrontend;
  private final int nByzantineServers;
  private final int nByzantineUsers;
  private final int uMaxDistance;
  private final int byzantineQuorum;

  public ClientToServerFrontend(
      String uname,
      HACrypto hCrypto,
      Map<String, String> sURLs,
      int nByzantineServers,
      int nByzantineUsers,
      int uMaxDistance,
      HAToDHServerFrontend dhFrontend,
      int callTimeout,
      int maxNRetries) {
    this.uname = uname;
    this.hCrypto = hCrypto;
    this.nByzantineServers = nByzantineServers;
    this.nByzantineUsers = nByzantineUsers;
    this.uMaxDistance = uMaxDistance;
    this.byzantineQuorum = 2 * nByzantineServers + 1;
    this.dhFrontend = dhFrontend;
    this.callTimeout = callTimeout;
    this.maxNRetries = maxNRetries;
    this.channels = new ArrayList<>();
    this.stubs = new HashMap<>();
    for (Map.Entry<String, String> sURL : sURLs.entrySet()) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(sURL.getValue()).usePlaintext().build();
      this.channels.add(channel);
      this.stubs.put(sURL.getKey(), ClientServerServicesGrpc.newBlockingStub(channel));
    }
  }

  public static Report buildReport(
      String uname,
      Integer epoch,
      Location location,
      Map<String, ByteString> uIdProofs,
      Map<String, ServerIdProofs> sIdProofs) {
    Proof proof =
        Proof.newBuilder()
            .setUname(uname)
            .setEpoch(epoch)
            .setX(location.getX())
            .setY(location.getY())
            .build();
    UserReport uReport = UserReport.newBuilder().setProof(proof).putAllUIdProofs(uIdProofs).build();
    return Report.newBuilder().setUReport(uReport).putAllSIdProofs(sIdProofs).build();
  }

  public Location obtainUL(String uname, Integer epoch) {
    Set<String> correctServers = new HashSet<>();
    Map<String, Integer> exceptions = new HashMap<>();
    CountDownLatch countDownLatch = new CountDownLatch(this.byzantineQuorum);
    AtomicReference<ObtainULRepPayload> repPayload = new AtomicReference<>(null);
    for (String sName : this.stubs.keySet()) {
      new Thread(
              () ->
                  this.obtainULWorker(
                      epoch, correctServers, exceptions, countDownLatch, repPayload, uname, sName))
          .start();
    }
    await(countDownLatch);
    ObtainULRepPayload reply = repPayload.get();
    if (reply == null) throwException(exceptions);
    if (correctServers.size() < this.byzantineQuorum) {
      this.obtainULWriteBack(correctServers, uname, epoch, reply);
    }
    assert reply != null;
    return new Location(reply.getX(), reply.getY());
  }

  private void obtainULWorker(
      Integer epoch,
      Set<String> correctServers,
      Map<String, Integer> exceptions,
      CountDownLatch countDownLatch,
      AtomicReference<ObtainULRepPayload> repPayload,
      String uname,
      String sName) {
    try {
      repPayload.set(this.doObtainUL(uname, sName, epoch, countDownLatch, 0));
      synchronized (correctServers) {
        correctServers.add(sName);
      }
    } catch (HARuntimeException e) {
      if (!(e.getCause() instanceof InterruptedException)) {
        synchronized (exceptions) {
          Integer nExceptions = exceptions.putIfAbsent(e.getMessage(), 1);
          if (nExceptions != null) exceptions.put(e.getMessage(), nExceptions + 1);
        }
      }
    }
    countDownLatch.countDown();
  }

  private ObtainULRepPayload doObtainUL(
      String uname, String sName, Integer epoch, CountDownLatch countDownLatch, int nRetries) {

    if (countDownLatch.getCount() <= 0) throw new HARuntimeException(new InterruptedException());

    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());

    Header header = this.generateHeader(nonce, iv);
    ObtainULReqPayload payload =
        ObtainULReqPayload.newBuilder().setUname(uname).setEpoch(epoch).build();
    byte[] hmac =
        Crypto.hmac(session.getSecKey(), Bytes.concat(header.toByteArray(), payload.toByteArray()));

    byte[] cipheredPayload = Crypto.cipherBytesAES(session.getSecKey(), iv, payload.toByteArray());

    try {
      ObtainULRep reply =
          this.stubs
              .get(sName)
              .withDeadlineAfter(callTimeout, TimeUnit.SECONDS)
              .obtainUL(
                  ObtainULReq.newBuilder()
                      .setHeader(header)
                      .setCipheredPayload(ByteString.copyFrom(cipheredPayload))
                      .setHmac(ByteString.copyFrom(hmac))
                      .build());
      return onObtainULSuccess(
          uname, sName, epoch, nonce, reply, session, countDownLatch, nRetries);
    } catch (StatusRuntimeException e) {
      return onObtainULFailure(uname, sName, epoch, nonce, session, e, countDownLatch, nRetries);
    }
  }

  private ObtainULRepPayload onObtainULSuccess(
      String uname,
      String sName,
      Integer epoch,
      BigInteger nonce,
      ObtainULRep reply,
      Session session,
      CountDownLatch countDownLatch,
      int nRetries) {
    IvParameterSpec iv = new IvParameterSpec(reply.getIv().toByteArray());
    byte[] payload;
    try {
      payload =
          Crypto.decipherBytesAES(
              session.getSecKey(), iv, reply.getCipheredPayload().toByteArray());
    } catch (AssertError e) {
      if (nRetries >= maxNRetries) throw new HARuntimeException(e.getMessage());
      System.out.printf("Decryption Failed: %s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doObtainUL(uname, sName, epoch, countDownLatch, nRetries + 1);
    }

    try {
      this.hCrypto.checkAuthHmac(
          sName,
          reply.getHmac().toByteArray(),
          Bytes.concat(payload, nonce.toByteArray(), iv.getIV()));
    } catch (HARuntimeException e) {
      if (nRetries >= maxNRetries) throw new HARuntimeException(e.getMessage());
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doObtainUL(uname, sName, epoch, countDownLatch, nRetries + 1);
    }

    ObtainULRepPayload repPayload;
    try {
      repPayload = ObtainULRepPayload.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      if (nRetries >= maxNRetries) throw new HARuntimeException(e.getMessage());
      System.out.printf("Invalid Payload: %s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doObtainUL(uname, sName, epoch, countDownLatch, nRetries + 1);
    }
    Location location = new Location(repPayload.getX(), repPayload.getY());
    Report report =
        buildReport(
            uname, epoch, location, repPayload.getUIdProofsMap(), repPayload.getSIdProofsMap());
    if (!this.verifyReport(report)) {
      if (nRetries >= maxNRetries) throw new HARuntimeException("Invalid Location Proof");
      System.out.printf("Invalid Location Proof!%nRetrying (...)%n");
      sleep(1000);
      return this.doObtainUL(uname, sName, epoch, countDownLatch, nRetries + 1);
    }
    return repPayload;
  }

  private ObtainULRepPayload onObtainULFailure(
      String uname,
      String sName,
      Integer epoch,
      BigInteger nonce,
      Session session,
      StatusRuntimeException e,
      CountDownLatch countDownLatch,
      int nRetries) {
    if (nRetries >= maxNRetries) throw new HARuntimeException(e.getMessage());
    if (this.isToRetry(e)) {
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doObtainUL(uname, sName, epoch, countDownLatch, nRetries + 1);
    }
    try {
      this.hCrypto.checkErrorAuth(sName, e, nonce);
    } catch (HARuntimeException e1) {
      System.out.printf("Exception Validation Failed: %s%nRetrying (...)%n", e1.getMessage());
      sleep(1000);
      return this.doObtainUL(uname, sName, epoch, countDownLatch, nRetries + 1);
    }
    if (e.getStatus().getCode() == UNAUTHENTICATED) {
      session.invalidate();
      return this.doObtainUL(uname, sName, epoch, countDownLatch, nRetries + 1);
    }
    throw new HARuntimeException(e.getMessage());
  }

  public List<String> obtainUAtL(Integer epoch, Location location) {
    AtomicReference<List<String>> highest = new AtomicReference<>();
    CountDownLatch countDownLatch = new CountDownLatch(this.byzantineQuorum);
    Map<String, Integer> exceptions = new HashMap<>();
    for (String sName : this.stubs.keySet()) {
      new Thread(
              () ->
                  this.obtainUAtLWorker(
                      sName, epoch, location, highest, countDownLatch, exceptions))
          .start();
    }
    await(countDownLatch);
    List<String> unames = highest.get();
    if (unames == null) throwException(exceptions);
    return unames;
  }

  private void obtainUAtLWorker(
      String sName,
      Integer epoch,
      Location location,
      AtomicReference<List<String>> highest,
      CountDownLatch countDownLatch,
      Map<String, Integer> exceptions) {
    try {
      List<String> unames = this.obtainUAtL(sName, epoch, location, countDownLatch, 0);
      synchronized (highest) {
        if (highest.get() == null || unames.size() > highest.get().size()) {
          highest.set(unames);
        }
      }
    } catch (HARuntimeException e) {
      if (!(e.getCause() instanceof InterruptedException)) {
        synchronized (exceptions) {
          Integer nExceptions = exceptions.putIfAbsent(e.getMessage(), 1);
          if (nExceptions != null) exceptions.put(e.getMessage(), nExceptions + 1);
        }
      }
    }
    countDownLatch.countDown();
  }

  private List<String> obtainUAtL(
      String sName, Integer epoch, Location location, CountDownLatch countDownLatch, int nRetries) {

    if (countDownLatch.getCount() <= 0) throw new HARuntimeException(new InterruptedException());

    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    ObtainUAtLReqPayload payload =
        ObtainUAtLReqPayload.newBuilder()
            .setEpoch(epoch)
            .setX(location.getX())
            .setY(location.getY())
            .build();
    byte[] cipheredPayload = Crypto.cipherBytesAES(session.getSecKey(), iv, payload.toByteArray());
    byte[] hmac =
        Crypto.hmac(session.getSecKey(), Bytes.concat(header.toByteArray(), payload.toByteArray()));

    try {
      ObtainUAtLRep reply =
          this.stubs
              .get(sName)
              .withDeadlineAfter(callTimeout, TimeUnit.SECONDS)
              .obtainUAtL(
                  ObtainUAtLReq.newBuilder()
                      .setHeader(header)
                      .setCipheredPayload(ByteString.copyFrom(cipheredPayload))
                      .setHmac(ByteString.copyFrom(hmac))
                      .build());
      return this.onObtainUAtLSuccess(
          sName, session, nonce, epoch, location, reply, countDownLatch, nRetries);
    } catch (StatusRuntimeException e) {
      return this.onObtainUAtLFailure(
          sName, session, nonce, epoch, location, e, countDownLatch, nRetries);
    }
  }

  private List<String> onObtainUAtLSuccess(
      String sName,
      Session session,
      BigInteger nonce,
      Integer epoch,
      Location location,
      ObtainUAtLRep reply,
      CountDownLatch countDownLatch,
      int nRetries) {

    IvParameterSpec iv = new IvParameterSpec(reply.getIv().toByteArray());
    byte[] payload =
        Crypto.decipherBytesAES(session.getSecKey(), iv, reply.getCipheredPayload().toByteArray());

    try {
      this.hCrypto.checkAuthHmac(
          sName,
          reply.getHmac().toByteArray(),
          Bytes.concat(payload, nonce.toByteArray(), reply.getIv().toByteArray()));
    } catch (HARuntimeException e) {
      if (nRetries >= maxNRetries) throw new HARuntimeException(e.getMessage());
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.obtainUAtL(sName, epoch, location, countDownLatch, nRetries + 1);
    }

    ObtainUAtLRepPayload repPayload;
    try {
      repPayload = ObtainUAtLRepPayload.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      if (nRetries >= maxNRetries) throw new HARuntimeException(e.getMessage());
      System.out.printf("Invalid Payload: %s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.obtainUAtL(sName, epoch, location, countDownLatch, nRetries + 1);
    }

    List<String> unames = new ArrayList<>();
    for (Report report : repPayload.getReportsList()) {
      Proof proof = report.getUReport().getProof();
      Location proofLocation = new Location(proof.getX(), proof.getY());
      if (!location.isNearBy(proofLocation, this.uMaxDistance) || !this.verifyReport(report)) {
        if (nRetries >= maxNRetries) throw new HARuntimeException("Invalid Location Proof");
        System.out.printf("Invalid Location Proof!%nRetrying (...)%n");
        sleep(1000);
        return this.obtainUAtL(sName, epoch, location, countDownLatch, nRetries + 1);
      }
      unames.add(proof.getUname());
    }
    return unames;
  }

  private List<String> onObtainUAtLFailure(
      String sName,
      Session session,
      BigInteger nonce,
      Integer epoch,
      Location location,
      StatusRuntimeException e,
      CountDownLatch countDownLatch,
      int nRetries) {
    if (nRetries >= maxNRetries) throw new HARuntimeException(e.getMessage());
    if (this.isToRetry(e)) {
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.obtainUAtL(sName, epoch, location, countDownLatch, nRetries + 1);
    }
    try {
      this.hCrypto.checkErrorAuth(sName, e, nonce);
    } catch (HARuntimeException e1) {
      System.out.printf("Exception Validation Failed: %s%nRetrying (...)%n", e1.getMessage());
      sleep(1000);
      return this.obtainUAtL(sName, epoch, location, countDownLatch, nRetries + 1);
    }
    if (e.getStatus().getCode() == UNAUTHENTICATED) {
      session.invalidate();
      return this.obtainUAtL(sName, epoch, location, countDownLatch, nRetries + 1);
    }
    throw new HARuntimeException(e.getMessage());
  }

  public void obtainULWriteBack(
      Set<String> correctServers,
      String uname,
      Integer epoch,
      ObtainULRepPayload obtainULRepPayload) {
    CountDownLatch countDownLatch =
        new CountDownLatch(this.byzantineQuorum - correctServers.size());
    Map<String, Integer> exceptions = new HashMap<>();
    Set<String> incorrectServers = new HashSet<>(this.stubs.keySet());
    incorrectServers.removeAll(correctServers);
    for (String sName : incorrectServers) {
      new Thread(
              () ->
                  this.obtainULWriteBackWorker(
                      sName, uname, epoch, obtainULRepPayload, countDownLatch, exceptions))
          .start();
    }
    await(countDownLatch);
    if (exceptions.size() > 0) throwException(exceptions);
  }

  private void obtainULWriteBackWorker(
      String sName,
      String uname,
      Integer epoch,
      ObtainULRepPayload obtainULRepPayload,
      CountDownLatch countDownLatch,
      Map<String, Integer> exceptions) {
    try {
      this.obtainULWriteBackInit(sName, uname, epoch, obtainULRepPayload, countDownLatch);
    } catch (HARuntimeException e) {
      if ((!(e.getCause() instanceof InterruptedException))) {
        synchronized (exceptions) {
          Integer nExceptions = exceptions.putIfAbsent(e.getMessage(), 1);
          if (nExceptions != null) exceptions.put(e.getMessage(), nExceptions + 1);
        }
      }
    }
    countDownLatch.countDown();
  }

  private void obtainULWriteBackInit(
      String sName,
      String uname,
      Integer epoch,
      ObtainULRepPayload obtainULRepPayload,
      CountDownLatch countDownLatch) {

    Location location = new Location(obtainULRepPayload.getX(), obtainULRepPayload.getY());
    Report report =
        buildReport(
            uname,
            epoch,
            location,
            obtainULRepPayload.getUIdProofsMap(),
            obtainULRepPayload.getSIdProofsMap());
    this.doObtainULWriteBack(sName, epoch, report, countDownLatch, 0);
  }

  private void doObtainULWriteBack(
      String sName, Integer epoch, Report report, CountDownLatch countDownLatch, int nRetries) {

    if (countDownLatch.getCount() <= 0) throw new HARuntimeException(new InterruptedException());

    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    byte[] cipheredReport = Crypto.cipherBytesAES(session.getSecKey(), iv, report.toByteArray());
    long pow =
        this.hCrypto.generateProofOfWork(Bytes.concat(header.toByteArray(), report.toByteArray()));
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(header.toByteArray(), Longs.toByteArray(pow), report.toByteArray()));

    try {
      ObtainULWriteBackRep reply =
          this.stubs
              .get(sName)
              .withDeadlineAfter(callTimeout, TimeUnit.SECONDS)
              .obtainULWriteBack(
                  ObtainULWriteBackReq.newBuilder()
                      .setHeader(header)
                      .setPow(pow)
                      .setCipheredReport(ByteString.copyFrom(cipheredReport))
                      .setHmac(ByteString.copyFrom(hmac))
                      .build());
      this.onObtainULWriteBackSuccess(sName, epoch, nonce, report, reply, countDownLatch, nRetries);
    } catch (StatusRuntimeException e) {
      this.onObtainULWriteBackFailure(
          sName, session, epoch, nonce, report, e, countDownLatch, nRetries);
    }
  }

  private void onObtainULWriteBackSuccess(
      String sName,
      Integer epoch,
      BigInteger nonce,
      Report report,
      ObtainULWriteBackRep reply,
      CountDownLatch countDownLatch,
      int nRetries) {
    try {
      this.hCrypto.checkAuthHmac(sName, reply.getHmac().toByteArray(), nonce.toByteArray());
    } catch (HARuntimeException e) {
      if (nRetries >= maxNRetries) throw new HARuntimeException(e.getMessage());
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      this.doObtainULWriteBack(sName, epoch, report, countDownLatch, nRetries + 1);
    }
  }

  private void onObtainULWriteBackFailure(
      String sName,
      Session session,
      Integer epoch,
      BigInteger nonce,
      Report report,
      StatusRuntimeException e,
      CountDownLatch countDownLatch,
      int nRetries) {
    if (nRetries >= maxNRetries) throw new HARuntimeException(e.getMessage());
    if (this.isToRetry(e)) {
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      this.doObtainULWriteBack(sName, epoch, report, countDownLatch, nRetries + 1);
      return;
    }
    try {
      this.hCrypto.checkErrorAuth(sName, e, nonce);
    } catch (HARuntimeException e1) {
      System.out.printf("Exception Validation Failed: %s%nRetrying (...)%n", e1.getMessage());
      sleep(1000);
      this.doObtainULWriteBack(sName, epoch, report, countDownLatch, nRetries + 1);
      return;
    }
    if (e.getStatus().getCode() == UNAUTHENTICATED) {
      session.invalidate();
      this.doObtainULWriteBack(sName, epoch, report, countDownLatch, nRetries + 1);
      return;
    }
    throw new HARuntimeException(e.getMessage());
  }

  private boolean verifyReport(Report report) {

    Map<String, ByteString> uIdProofs = report.getUReport().getUIdProofsMap();

    int nUIdProofs = uIdProofs.size();
    if (nUIdProofs < this.nByzantineUsers) return false;

    byte[] uProofHash = Crypto.hash(report.getUReport().getProof().toByteArray());

    Set<String> seenUSigners = new HashSet<>();
    seenUSigners.add(report.getUReport().getProof().getUname());
    int nUInvalidIdProofs = 0;

    for (Map.Entry<String, ByteString> uIdProof :
        report.getUReport().getUIdProofsMap().entrySet()) {
      String uSigner = uIdProof.getKey();
      byte[] uSignedProof = uIdProof.getValue().toByteArray();

      try {
        if (!this.hCrypto.isUser(uSigner)) nUInvalidIdProofs++;
        else {
          byte[] uProofUnsigned = this.hCrypto.unsignPayload(uSigner, uSignedProof);
          if (!Arrays.equals(uProofUnsigned, uProofHash) || !seenUSigners.add(uIdProof.getKey())) {
            nUInvalidIdProofs++;
          } else {
            // Valid User Signature
            if (!this.verifyServerIdProofs(report, uSigner)) nUInvalidIdProofs++;
          }
        }
      } catch (AssertError e) {
        nUInvalidIdProofs++;
      }
      if (nUIdProofs - nUInvalidIdProofs < this.nByzantineUsers) return false;
    }
    return true;
  }

  private boolean verifyServerIdProofs(Report report, String uSigner) {

    if (!report.getSIdProofsMap().containsKey(uSigner)) return false;

    Map<String, ByteString> sIdProofs =
        report.getSIdProofsMap().get(uSigner).getSIdProofsValuesMap();

    int nSIdProofs = sIdProofs.size();
    if (nSIdProofs < this.byzantineQuorum) return false;

    byte[] sProofHash =
        Crypto.hash(Bytes.concat(report.getUReport().getProof().toByteArray(), uSigner.getBytes()));

    Set<String> seenSSigners = new HashSet<>();
    int nSInvalidIdProofs = 0;

    for (Map.Entry<String, ByteString> sIdProof : sIdProofs.entrySet()) {
      String sSigner = sIdProof.getKey();
      byte[] sSignedProof = sIdProof.getValue().toByteArray();

      try {
        if (!this.hCrypto.isServer(sSigner)) nSInvalidIdProofs++;
        else {
          byte[] sProofUnsigned = this.hCrypto.unsignPayload(sSigner, sSignedProof);
          if (!Arrays.equals(sProofUnsigned, sProofHash) || !seenSSigners.add(sSigner)) {
            nSInvalidIdProofs++;
          }
        }
      } catch (AssertError e) {
        nSInvalidIdProofs++;
      }
      if (nSIdProofs - nSInvalidIdProofs < byzantineQuorum) return false;
    }
    return true;
  }

  private Header generateHeader(BigInteger nonce, IvParameterSpec iv) {
    return Header.newBuilder()
        .setUname(this.uname)
        .setNonce(ByteString.copyFrom(nonce.toByteArray()))
        .setIv(ByteString.copyFrom(iv.getIV()))
        .build();
  }

  private boolean isToRetry(StatusRuntimeException e) {
    return e.getStatus().getCode() == PERMISSION_DENIED
        || e.getStatus().getCode() == INVALID_ARGUMENT
        || e.getStatus().getCode() == UNAVAILABLE
        || e.getStatus().getCode() == DEADLINE_EXCEEDED;
  }

  private void throwException(Map<String, Integer> exceptions) {
    String errorMsg;
    synchronized (exceptions) {
      errorMsg =
          exceptions.entrySet().stream()
              .filter(e -> e.getValue() > this.nByzantineServers)
              .map(Map.Entry::getKey)
              .findAny()
              .orElse("Unknown Error!");
    }
    throw new HARuntimeException(errorMsg);
  }

  public void shutdown() {
    this.channels.forEach(ManagedChannel::shutdown);
  }
}
