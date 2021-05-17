package pt.tecnico.ulisboa.hds.hdlt.user.api;

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
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.lib.error.AssertError;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;
import pt.tecnico.ulisboa.hds.hdlt.user.session.Session;

import javax.crypto.spec.IvParameterSpec;
import java.math.BigInteger;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.grpc.Status.Code.*;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.await;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.sleep;

public class ClientToServerFrontend {

  private final int callTimeout;
  private final int maxNRetries;
  private final String uname;
  private final GridManager grid;
  private final UserCrypto uCrypto;
  private final UserToDHServerFrontend dhFrontend;
  private final List<ManagedChannel> channels;
  private final Map<String, ClientServerServicesBlockingStub> stubs;
  private final int byzantineQuorum;
  private final int nByzantineUsers;
  private final int nByzantineServers;

  public ClientToServerFrontend(
      String uname,
      GridManager grid,
      UserCrypto uCrypto,
      Map<String, String> sURLs,
      int nByzantineServers,
      int nByzantineUsers,
      UserToDHServerFrontend dhFrontend,
      int callTimeout,
      int maxNRetries) {
    this.uname = uname;
    this.grid = grid;
    this.uCrypto = uCrypto;
    this.nByzantineServers = nByzantineServers;
    this.byzantineQuorum = 2 * nByzantineServers + 1;
    this.nByzantineUsers = nByzantineUsers;
    this.dhFrontend = dhFrontend;
    this.callTimeout = callTimeout;
    this.maxNRetries = maxNRetries;
    this.channels = new ArrayList<>();
    this.stubs = new HashMap<>();
    for (Entry<String, String> sURL : sURLs.entrySet()) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(sURL.getValue()).usePlaintext().build();
      this.channels.add(channel);
      this.stubs.put(sURL.getKey(), ClientServerServicesGrpc.newBlockingStub(channel));
    }
  }

  public static UserReport buildUserReport(
      String uname, Integer epoch, Location location, Map<String, ByteString> uIdProofs) {
    Proof proof =
        Proof.newBuilder()
            .setUname(uname)
            .setEpoch(epoch)
            .setX(location.getX())
            .setY(location.getY())
            .build();
    return UserReport.newBuilder().setProof(proof).putAllUIdProofs(uIdProofs).build();
  }

  public static Report buildReport(
      String uname,
      Integer epoch,
      Location location,
      Map<String, ByteString> uIdProofs,
      Map<String, ServerIdProofs> sIdProofs) {
    UserReport uReport = buildUserReport(uname, epoch, location, uIdProofs);
    return Report.newBuilder().setUReport(uReport).putAllSIdProofs(sIdProofs).build();
  }

  public void submitULReport(Integer epoch, Map<String, byte[]> idProofs) {
    CountDownLatch countDownLatch = new CountDownLatch(this.byzantineQuorum);
    Map<String, Integer> exceptions = new HashMap<>();
    Stream<Entry<String, byte[]>> uIdProofsStream = idProofs.entrySet().stream();
    Map<String, ByteString> uIdProofs =
        uIdProofsStream.collect(
            Collectors.toMap(Entry::getKey, idProof -> ByteString.copyFrom(idProof.getValue())));
    UserReport uReport =
        buildUserReport(this.uname, epoch, this.grid.getLocation(this.uname, epoch), uIdProofs);
    for (String sName : this.stubs.keySet()) {
      new Thread(() -> this.submitULReportWorker(sName, uReport, countDownLatch, exceptions))
          .start();
    }
    await(countDownLatch);
    if (exceptions.size() > 0) throwException(exceptions, null);
  }

  public void submitULReportWorker(
      String sName,
      UserReport uReport,
      CountDownLatch countDownLatch,
      Map<String, Integer> exceptions) {
    try {
      this.doSubmitULReport(sName, uReport, countDownLatch, 0);
    } catch (UserRuntimeException e) {
      if ((!(e.getCause() instanceof InterruptedException))) {
        synchronized (exceptions) {
          Integer nExceptions = exceptions.putIfAbsent(e.getMessage(), 1);
          if (nExceptions != null) exceptions.put(e.getMessage(), nExceptions + 1);
        }
      }
    }
    countDownLatch.countDown();
  }

  private void doSubmitULReport(
      String sName, UserReport uReport, CountDownLatch countDownLatch, int nRetries) {

    if (countDownLatch.getCount() <= 0) throw new UserRuntimeException(new InterruptedException());

    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    byte[] cipheredUserReport =
        Crypto.cipherBytesAES(session.getSecKey(), iv, uReport.toByteArray());
    long pow =
        this.uCrypto.generateProofOfWork(Bytes.concat(header.toByteArray(), uReport.toByteArray()));
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(header.toByteArray(), Longs.toByteArray(pow), uReport.toByteArray()));

    try {
      SubmitULReportRep reply =
          this.stubs
              .get(sName)
              .withDeadlineAfter(callTimeout, TimeUnit.SECONDS)
              .submitULReport(
                  SubmitULReportReq.newBuilder()
                      .setHeader(header)
                      .setPow(pow)
                      .setCipheredUserReport(ByteString.copyFrom(cipheredUserReport))
                      .setHmac(ByteString.copyFrom(hmac))
                      .build());
      this.onSubmitULReportSuccess(sName, session, nonce, uReport, reply, countDownLatch, nRetries);
    } catch (StatusRuntimeException e) {
      this.onSubmitULReportFailure(sName, session, nonce, uReport, e, countDownLatch, nRetries);
    }
  }

  private void onSubmitULReportSuccess(
      String sName,
      Session session,
      BigInteger nonce,
      UserReport report,
      SubmitULReportRep reply,
      CountDownLatch countDownLatch,
      int nRetries) {
    try {
      this.uCrypto.checkAuthHmac(session, reply.getHmac().toByteArray(), nonce.toByteArray());
    } catch (UserRuntimeException e) {
      if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      this.doSubmitULReport(sName, report, countDownLatch, nRetries + 1);
    }
  }

  private void onSubmitULReportFailure(
      String sName,
      Session session,
      BigInteger nonce,
      UserReport report,
      StatusRuntimeException e,
      CountDownLatch countDownLatch,
      int nRetries) {
    if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
    if (this.isToRetry(e)) {
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      this.doSubmitULReport(sName, report, countDownLatch, nRetries + 1);
      return;
    }
    try {
      this.uCrypto.checkErrorAuth(sName, e, nonce);
    } catch (UserRuntimeException e1) {
      System.out.printf("Exception Validation Failed: %s%nRetrying (...)%n", e1.getMessage());
      sleep(1000);
      this.doSubmitULReport(sName, report, countDownLatch, nRetries + 1);
      return;
    }
    if (e.getStatus().getCode() == UNAUTHENTICATED) {
      session.invalidate();
      this.doSubmitULReport(sName, report, countDownLatch, nRetries + 1);
      return;
    }
    throw new UserRuntimeException(e.getMessage());
  }

  public Location obtainUL(Integer epoch) {
    Set<String> correctServers = new HashSet<>();
    Map<String, Integer> exceptions = new HashMap<>();
    CountDownLatch countDownLatch = new CountDownLatch(this.byzantineQuorum);
    AtomicReference<ObtainULRepPayload> repPayload = new AtomicReference<>(null);
    for (String sName : this.stubs.keySet()) {
      new Thread(
              () ->
                  this.obtainULWorker(
                      epoch, correctServers, exceptions, countDownLatch, repPayload, sName))
          .start();
    }
    await(countDownLatch);
    ObtainULRepPayload reply = repPayload.get();
    if (reply == null) throwException(exceptions, "Unknown Error!");
    assert reply != null;
    Location location = new Location(reply.getX(), reply.getY());
    if (correctServers.size() < this.byzantineQuorum) {
      this.obtainULWriteBack(
          correctServers, epoch, location, reply.getUIdProofsMap(), reply.getSIdProofsMap());
    }
    return location;
  }

  private void obtainULWorker(
      Integer epoch,
      Set<String> correctServers,
      Map<String, Integer> exceptions,
      CountDownLatch countDownLatch,
      AtomicReference<ObtainULRepPayload> repPayload,
      String sName) {
    try {
      repPayload.set(this.doObtainUL(sName, epoch, countDownLatch, 0));
      synchronized (correctServers) {
        correctServers.add(sName);
      }
    } catch (UserRuntimeException e) {
      if (!(e.getCause() instanceof InterruptedException)) {
        synchronized (exceptions) {
          Integer nExceptions = exceptions.putIfAbsent(e.getMessage(), 1);
          if (nExceptions != null) exceptions.put(e.getMessage(), nExceptions + 1);
        }
      }
    }
    countDownLatch.countDown();
  }

  public ObtainULRepPayload doObtainUL(
      String sName, Integer epoch, CountDownLatch countDownLatch, int nRetries) {

    if (countDownLatch.getCount() <= 0) throw new UserRuntimeException(new InterruptedException());

    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    ObtainULReqPayload reqPayload =
        ObtainULReqPayload.newBuilder().setUname(this.uname).setEpoch(epoch).build();
    byte[] cipheredPayload =
        Crypto.cipherBytesAES(session.getSecKey(), iv, reqPayload.toByteArray());
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(), Bytes.concat(header.toByteArray(), reqPayload.toByteArray()));

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
      return this.onObtainULSuccess(sName, epoch, nonce, reply, session, countDownLatch, nRetries);
    } catch (StatusRuntimeException e) {
      return this.onObtainULFailure(sName, epoch, nonce, session, e, countDownLatch, nRetries);
    }
  }

  private ObtainULRepPayload onObtainULSuccess(
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
      if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
      System.out.printf("Decryption Failed: %s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doObtainUL(sName, epoch, countDownLatch, nRetries + 1);
    }

    try {
      this.uCrypto.checkAuthHmac(
          session,
          reply.getHmac().toByteArray(),
          Bytes.concat(payload, nonce.toByteArray(), iv.getIV()));
    } catch (UserRuntimeException e) {
      if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doObtainUL(sName, epoch, countDownLatch, nRetries + 1);
    }

    ObtainULRepPayload repPayload;
    try {
      repPayload = ObtainULRepPayload.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
      System.out.printf("Invalid Payload: %s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doObtainUL(sName, epoch, countDownLatch, nRetries + 1);
    }
    Location location = new Location(repPayload.getX(), repPayload.getY());
    if (!this.isReportValid(
        buildReport(
            this.uname,
            epoch,
            location,
            repPayload.getUIdProofsMap(),
            repPayload.getSIdProofsMap()))) {
      if (nRetries >= maxNRetries) throw new UserRuntimeException("Invalid Location Proof");
      System.out.printf("Invalid Location Proof!%nRetrying (...)%n");
      sleep(1000);
      return this.doObtainUL(sName, epoch, countDownLatch, nRetries + 1);
    }
    return repPayload;
  }

  private ObtainULRepPayload onObtainULFailure(
      String sName,
      Integer epoch,
      BigInteger nonce,
      Session session,
      StatusRuntimeException e,
      CountDownLatch countDownLatch,
      int nRetries) {
    if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
    if (this.isToRetry(e)) {
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doObtainUL(sName, epoch, countDownLatch, nRetries + 1);
    }
    try {
      this.uCrypto.checkErrorAuth(sName, e, nonce);
    } catch (UserRuntimeException e1) {
      System.out.printf("Exception Validation Failed: %s%nRetrying (...)%n", e1.getMessage());
      sleep(1000);
      return this.doObtainUL(sName, epoch, countDownLatch, nRetries + 1);
    }
    if (e.getStatus().getCode() == UNAUTHENTICATED) {
      session.invalidate();
      return this.doObtainUL(sName, epoch, countDownLatch, nRetries + 1);
    }
    throw new UserRuntimeException(e.getMessage());
  }

  private boolean isReportValid(Report report) {

    Map<String, ByteString> uIdProofs = report.getUReport().getUIdProofsMap();
    int nUIdProofs = uIdProofs.size();
    if (nUIdProofs < this.nByzantineUsers) return false;

    byte[] proofHash = Crypto.hash(report.getUReport().getProof().toByteArray());

    Set<String> seenUSigners = new HashSet<>();
    seenUSigners.add(this.uname);
    int nUInvalidIdProofs = 0;

    for (Entry<String, ByteString> uIdProof : report.getUReport().getUIdProofsMap().entrySet()) {
      String uSigner = uIdProof.getKey();
      byte[] uSignedProof = uIdProof.getValue().toByteArray();

      try {
        if (!this.uCrypto.isUser(uSigner)) nUInvalidIdProofs++;
        else {
          byte[] uProofUnsigned = this.uCrypto.unsignPayload(uSigner, uSignedProof);
          if (!Arrays.equals(uProofUnsigned, proofHash) || !seenUSigners.add(uSigner)) {
            nUInvalidIdProofs++;
          } else {
            // Valid User Signature
            if (!this.verifyServerIdProofs(report, uSigner)) nUInvalidIdProofs++;
          }
        }
      } catch (AssertError e) {
        nUInvalidIdProofs++;
      }
      if (nUIdProofs - nUInvalidIdProofs < nByzantineUsers) return false;
    }
    return true;
  }

  private boolean verifyServerIdProofs(Report report, String uSigner) {

    if (!report.getSIdProofsMap().containsKey(uSigner)) return false;

    Map<String, ByteString> sIdProofs =
        report.getSIdProofsMap().get(uSigner).getSIdProofsValuesMap();

    return this.verifyServerIdProofs(report.getUReport().getProof(), uSigner, sIdProofs);
  }

  private boolean verifyServerIdProofs(
      Proof proof, String uSigner, Map<String, ByteString> sIdProofs) {
    int nSIdProofs = sIdProofs.size();
    if (nSIdProofs < this.byzantineQuorum) return false;

    byte[] sProofHash = Crypto.hash(Bytes.concat(proof.toByteArray(), uSigner.getBytes()));

    Set<String> seenSSigners = new HashSet<>();
    int nSInvalidIdProofs = 0;

    for (Entry<String, ByteString> sIdProof : sIdProofs.entrySet()) {
      String sSigner = sIdProof.getKey();
      byte[] sSignedProof = sIdProof.getValue().toByteArray();

      try {
        if (!this.uCrypto.isServer(sSigner)) nSInvalidIdProofs++;
        else {
          byte[] sProofUnsigned = this.uCrypto.unsignPayload(sSigner, sSignedProof);
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

  public Map<Integer, List<String>> requestMyProofs(List<Integer> epochs) {
    AtomicReference<Map<Integer, List<String>>> highest = new AtomicReference<>();
    CountDownLatch countDownLatch = new CountDownLatch(this.byzantineQuorum);
    Map<String, Integer> exceptions = new HashMap<>();
    for (String sName : this.stubs.keySet()) {
      new Thread(
              () -> this.requestMyProofsWorker(sName, epochs, highest, countDownLatch, exceptions))
          .start();
    }
    await(countDownLatch);
    Map<Integer, List<String>> myProofs = highest.get();
    if (myProofs == null) throwException(exceptions, "Unknown Error!");
    return myProofs;
  }

  private void requestMyProofsWorker(
      String sName,
      List<Integer> epochs,
      AtomicReference<Map<Integer, List<String>>> highest,
      CountDownLatch countDownLatch,
      Map<String, Integer> exceptions) {
    try {
      Map<Integer, List<String>> reply = this.requestMyProofs(sName, epochs, countDownLatch);
      synchronized (highest) {
        if (highest.get() == null) highest.set(reply);
        else {
          int replySize = reply.values().stream().mapToInt(List::size).sum();
          int highestSize = highest.get().values().stream().mapToInt(List::size).sum();
          if (replySize > highestSize) highest.set(reply);
        }
      }
    } catch (UserRuntimeException e) {
      if (!(e.getCause() instanceof InterruptedException)) {
        synchronized (exceptions) {
          Integer nExceptions = exceptions.putIfAbsent(e.getMessage(), 1);
          if (nExceptions != null) exceptions.put(e.getMessage(), nExceptions + 1);
        }
      }
    }
    countDownLatch.countDown();
  }

  private Map<Integer, List<String>> requestMyProofs(
      String sName, List<Integer> epochs, CountDownLatch countDownLatch) {
    RequestMyProofsReqPayload payload =
        RequestMyProofsReqPayload.newBuilder().addAllEpochs(epochs).build();
    return this.doRequestMyProofs(sName, payload, countDownLatch, 0);
  }

  private Map<Integer, List<String>> doRequestMyProofs(
      String sName,
      RequestMyProofsReqPayload reqPayload,
      CountDownLatch countDownLatch,
      int nRetries) {

    if (countDownLatch.getCount() <= 0) throw new UserRuntimeException(new InterruptedException());

    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    byte[] cipheredPayload =
        Crypto.cipherBytesAES(session.getSecKey(), iv, reqPayload.toByteArray());

    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(), Bytes.concat(header.toByteArray(), reqPayload.toByteArray()));

    try {
      RequestMyProofsRep reply =
          this.stubs
              .get(sName)
              .withDeadlineAfter(callTimeout, TimeUnit.SECONDS)
              .requestMyProofs(
                  RequestMyProofsReq.newBuilder()
                      .setHeader(header)
                      .setCipheredPayload(ByteString.copyFrom(cipheredPayload))
                      .setHmac(ByteString.copyFrom(hmac))
                      .build());
      return this.onRequestMyProofsSuccess(
          sName, session, nonce, reqPayload, reply, countDownLatch, nRetries);
    } catch (StatusRuntimeException e) {
      return this.onRequestMyProofsFailure(
          sName, session, nonce, reqPayload, e, countDownLatch, nRetries);
    }
  }

  private Map<Integer, List<String>> onRequestMyProofsSuccess(
      String sName,
      Session session,
      BigInteger nonce,
      RequestMyProofsReqPayload reqPayload,
      RequestMyProofsRep reply,
      CountDownLatch countDownLatch,
      int nRetries) {

    IvParameterSpec iv = new IvParameterSpec(reply.getIv().toByteArray());

    byte[] bytesRepPayload;
    try {
      bytesRepPayload =
          Crypto.decipherBytesAES(
              session.getSecKey(), iv, reply.getCipheredPayload().toByteArray());
    } catch (AssertError e) {
      if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
      System.out.printf("Decryption Failed: %s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doRequestMyProofs(sName, reqPayload, countDownLatch, nRetries + 1);
    }

    try {
      this.uCrypto.checkAuthHmac(
          session,
          reply.getHmac().toByteArray(),
          Bytes.concat(bytesRepPayload, nonce.toByteArray(), iv.getIV()));
    } catch (UserRuntimeException e) {
      if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doRequestMyProofs(sName, reqPayload, countDownLatch, nRetries + 1);
    }

    RequestMyProofsRepPayload repPayload;
    try {
      repPayload = RequestMyProofsRepPayload.parseFrom(bytesRepPayload);
    } catch (InvalidProtocolBufferException e) {
      if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
      System.out.printf("Invalid Payload: %s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doRequestMyProofs(sName, reqPayload, countDownLatch, nRetries + 1);
    }
    return this.getProofs(sName, reqPayload, repPayload, countDownLatch, nRetries);
  }

  private Map<Integer, List<String>> getProofs(
      String sName,
      RequestMyProofsReqPayload reqPayload,
      RequestMyProofsRepPayload repPayload,
      CountDownLatch countDownLatch,
      int nRetries) {
    Map<Integer, List<String>> proofsList = new HashMap<>();

    for (AuthProof authProof : repPayload.getAuthProofsList()) {
      try {
        this.uCrypto.checkAuthSignature(
            this.uname,
            authProof.getSignedProof().toByteArray(),
            authProof.getProof().toByteArray());
        Proof proof = authProof.getProof();
        if (!proofsList.containsKey(proof.getEpoch())) {
          proofsList.put(proof.getEpoch(), new ArrayList<>());
        }
        if (!this.verifyServerIdProofs(
            proof, this.uname, authProof.getSIdProofsValues().getSIdProofsValuesMap())) {
          throw new UserRuntimeException("");
        }
        proofsList.get(proof.getEpoch()).add(this.parseProof(proof));
      } catch (UserRuntimeException e) {
        if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
        System.out.printf("Invalid Payload: %s%nRetrying (...)%n", e);
        sleep(1000);
        return this.doRequestMyProofs(sName, reqPayload, countDownLatch, nRetries + 1);
      }
    }
    return proofsList;
  }

  private Map<Integer, List<String>> onRequestMyProofsFailure(
      String sName,
      Session session,
      BigInteger nonce,
      RequestMyProofsReqPayload reqPayload,
      StatusRuntimeException e,
      CountDownLatch countDownLatch,
      int nRetries) {
    if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
    if (this.isToRetry(e)) {
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.doRequestMyProofs(sName, reqPayload, countDownLatch, nRetries + 1);
    }
    try {
      this.uCrypto.checkErrorAuth(sName, e, nonce);
    } catch (UserRuntimeException e1) {
      System.out.printf("Exception Validation Failed: %s%nRetrying (...)%n", e1.getMessage());
      sleep(1000);
      return this.doRequestMyProofs(sName, reqPayload, countDownLatch, nRetries + 1);
    }
    if (e.getStatus().getCode() == UNAUTHENTICATED) {
      session.invalidate();
      return this.doRequestMyProofs(sName, reqPayload, countDownLatch, nRetries + 1);
    }
    throw new UserRuntimeException(e.getMessage());
  }

  public void obtainULWriteBack(
      Set<String> correctServers,
      Integer epoch,
      Location location,
      Map<String, ByteString> uIdProofs,
      Map<String, ServerIdProofs> sIdProofs) {
    CountDownLatch countDownLatch =
        new CountDownLatch(this.byzantineQuorum - correctServers.size());
    Map<String, Integer> exceptions = new HashMap<>();
    Set<String> incorrectServers = new HashSet<>(this.stubs.keySet());
    incorrectServers.removeAll(correctServers);
    Report report = buildReport(this.uname, epoch, location, uIdProofs, sIdProofs);
    for (String sName : incorrectServers) {
      new Thread(() -> this.obtainULWriteBackWorker(sName, report, countDownLatch, exceptions))
          .start();
    }
    await(countDownLatch);
    if (exceptions.size() > 0) throwException(exceptions, null);
  }

  private void obtainULWriteBackWorker(
      String sName, Report report, CountDownLatch countDownLatch, Map<String, Integer> exceptions) {
    try {
      this.doObtainULWriteBack(sName, report, countDownLatch, 0);
    } catch (UserRuntimeException e) {
      if ((!(e.getCause() instanceof InterruptedException))) {
        synchronized (exceptions) {
          Integer nExceptions = exceptions.putIfAbsent(e.getMessage(), 1);
          if (nExceptions != null) exceptions.put(e.getMessage(), nExceptions + 1);
        }
      }
    }
    countDownLatch.countDown();
  }

  private void doObtainULWriteBack(
      String sName, Report report, CountDownLatch countDownLatch, int nRetries) {

    if (countDownLatch.getCount() <= 0) throw new UserRuntimeException(new InterruptedException());

    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    byte[] cipheredReport = Crypto.cipherBytesAES(session.getSecKey(), iv, report.toByteArray());
    long pow =
        this.uCrypto.generateProofOfWork(Bytes.concat(header.toByteArray(), report.toByteArray()));
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
      this.onObtainULWriteBackSuccess(
          sName, session, nonce, report, reply, countDownLatch, nRetries);
    } catch (StatusRuntimeException e) {
      this.onObtainULWriteBackFailure(sName, session, nonce, report, e, countDownLatch, nRetries);
    }
  }

  private void onObtainULWriteBackSuccess(
      String sName,
      Session session,
      BigInteger nonce,
      Report report,
      ObtainULWriteBackRep reply,
      CountDownLatch countDownLatch,
      int nRetries) {
    try {
      this.uCrypto.checkAuthHmac(session, reply.getHmac().toByteArray(), nonce.toByteArray());
    } catch (UserRuntimeException e) {
      if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      this.doObtainULWriteBack(sName, report, countDownLatch, nRetries + 1);
    }
  }

  private void onObtainULWriteBackFailure(
      String sName,
      Session session,
      BigInteger nonce,
      Report report,
      StatusRuntimeException e,
      CountDownLatch countDownLatch,
      int nRetries) {
    if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
    if (this.isToRetry(e)) {
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      this.doObtainULWriteBack(sName, report, countDownLatch, nRetries + 1);
      return;
    }
    try {
      this.uCrypto.checkErrorAuth(sName, e, nonce);
    } catch (UserRuntimeException e1) {
      System.out.printf("Exception Validation Failed: %s%nRetrying (...)%n", e1.getMessage());
      sleep(1000);
      this.doObtainULWriteBack(sName, report, countDownLatch, nRetries + 1);
      return;
    }
    if (e.getStatus().getCode() == UNAUTHENTICATED) {
      session.invalidate();
      this.doObtainULWriteBack(sName, report, countDownLatch, nRetries + 1);
      return;
    }
    throw new UserRuntimeException(e.getMessage());
  }

  private boolean isToRetry(StatusRuntimeException e) {
    return e.getStatus().getCode() == PERMISSION_DENIED
        || e.getStatus().getCode() == INVALID_ARGUMENT
        || e.getStatus().getCode() == UNAVAILABLE
        || e.getStatus().getCode() == DEADLINE_EXCEEDED;
  }

  private void throwException(Map<String, Integer> exceptions, String defaultMessage)
      throws UserRuntimeException {
    String errorMsg;
    synchronized (exceptions) {
      errorMsg =
          exceptions.entrySet().stream()
              .filter(e -> e.getValue() > this.nByzantineServers)
              .map(Entry::getKey)
              .findAny()
              .orElse(defaultMessage);
    }
    if (errorMsg != null) throw new UserRuntimeException(errorMsg);
  }

  private String parseProof(Proof proof) {
    return String.format(
        "- %s at Location: (%d, %d)!%n", proof.getUname(), proof.getX(), proof.getY());
  }

  private Header generateHeader(BigInteger nonce, IvParameterSpec iv) {
    return Header.newBuilder()
        .setUname(this.uname)
        .setNonce(ByteString.copyFrom(nonce.toByteArray()))
        .setIv(ByteString.copyFrom(iv.getIV()))
        .build();
  }

  public void shutdown() {
    this.channels.forEach(ManagedChannel::shutdown);
  }
}
