package pt.tecnico.ulisboa.hds.hdlt.server.api.adeb;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.stub.StreamObserver;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Proof;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Report;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.ServerIdProofs;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.UserReport;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesGrpc.ADEBServicesImplBase;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesOuterClass.EchoRep;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesOuterClass.EchoReq;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesOuterClass.ReadyRep;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesOuterClass.ReadyReq;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ServerCrypto;
import pt.tecnico.ulisboa.hds.hdlt.server.api.adeb.ADEBInstance.ADEBMetadata;
import pt.tecnico.ulisboa.hds.hdlt.server.error.ServerStatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.server.session.Session;

import javax.crypto.spec.IvParameterSpec;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import static io.grpc.Status.*;
import static pt.tecnico.ulisboa.hds.hdlt.server.api.CommonServices.verifyReport;
import static pt.tecnico.ulisboa.hds.hdlt.server.api.CommonServices.verifySession;

public class ADEBServicesImpl extends ADEBServicesImplBase {

  private final String mySName;
  private final ServerCrypto sCrypto;
  private final ADEBInstanceManager adebInstanceManager;
  private final ADEBFrontend adebFrontend;
  private final int nByzantineUsers;
  private final int nByzantineServers;
  private final int byzantineQuorum;

  public ADEBServicesImpl(
      String mySName,
      ServerCrypto sCrypto,
      ADEBInstanceManager adebInstanceManager,
      ADEBFrontend adebFrontend,
      int nByzantineUsers,
      int nByzantineServers) {
    this.mySName = mySName;
    this.sCrypto = sCrypto;
    this.adebInstanceManager = adebInstanceManager;
    this.adebFrontend = adebFrontend;
    this.nByzantineUsers = nByzantineUsers;
    this.nByzantineServers = nByzantineServers;
    this.byzantineQuorum = 2 * nByzantineServers + 1;
  }

  @Override
  public void echo(EchoReq req, StreamObserver<EchoRep> resObs) {

    BigInteger nonce = new BigInteger(1, req.getHeader().getNonce().toByteArray());
    if (!this.sCrypto.isServer(req.getHeader().getSName())) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, PERMISSION_DENIED, "Permission Denied!", nonce);
    }

    Session session = this.sCrypto.getSessionAsServer(req.getHeader().getSName());
    verifySession(this.sCrypto, session, nonce);

    IvParameterSpec iv = new IvParameterSpec(req.getHeader().getIv().toByteArray());
    byte[] payload;
    try {
      payload =
          Crypto.decipherBytesAES(
              session.getSecKey(), iv, req.getCipheredUserReport().toByteArray());
    } catch (AssertionError e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, UNAUTHENTICATED, "Invalid Session!", nonce);
    }

    this.sCrypto.checkAuthHmac(
        session,
        req.getHmac().toByteArray(),
        Bytes.concat(req.getHeader().toByteArray(), payload),
        nonce);

    UserReport uReport;
    try {
      uReport = UserReport.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, INVALID_ARGUMENT, "Invalid Payload!", nonce);
    }

    verifyReport(
        this.sCrypto,
        this.nByzantineUsers,
        this.byzantineQuorum,
        nonce,
        Report.newBuilder().setUReport(uReport).build(),
        false);

    Proof proof = uReport.getProof();
    ADEBInstance instance =
        this.adebInstanceManager.getInstance(proof.getUname(), proof.getEpoch(), nonce);
    if (instance != null) {
      synchronized (instance) {
        if (instance.addEcho(req.getHeader().getSName(), uReport)) {
          ADEBMetadata metadata = instance.getMetadata(uReport);
          if (!instance.hasSentReady(this.mySName)
              && metadata.echoCounter() >= this.byzantineQuorum) {
            this.sendReady(uReport, instance, metadata);
          }
        }
      }
    }

    byte[] hmac = Crypto.hmac(session.getSecKey(), nonce.toByteArray());
    resObs.onNext(EchoRep.newBuilder().setHmac(ByteString.copyFrom(hmac)).build());
    resObs.onCompleted();
  }

  @Override
  public void ready(ReadyReq req, StreamObserver<ReadyRep> resObs) {

    BigInteger nonce = new BigInteger(1, req.getHeader().getNonce().toByteArray());
    if (!this.sCrypto.isServer(req.getHeader().getSName())) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, PERMISSION_DENIED, "Permission Denied!", nonce);
    }

    Session session = this.sCrypto.getSessionAsServer(req.getHeader().getSName());
    verifySession(this.sCrypto, session, nonce);

    IvParameterSpec iv = new IvParameterSpec(req.getHeader().getIv().toByteArray());
    byte[] payload;
    try {
      payload =
          Crypto.decipherBytesAES(session.getSecKey(), iv, req.getCipheredReport().toByteArray());
    } catch (AssertionError e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, UNAUTHENTICATED, "Invalid Session!", nonce);
    }

    this.sCrypto.checkAuthHmac(
        session,
        req.getHmac().toByteArray(),
        Bytes.concat(req.getHeader().toByteArray(), payload),
        nonce);

    Report report;
    try {
      report = Report.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, INVALID_ARGUMENT, "Invalid Payload!", nonce);
    }

    verifyReport(this.sCrypto, this.nByzantineUsers, this.byzantineQuorum, nonce, report, false);

    UserReport uReport = report.getUReport();
    Proof proof = uReport.getProof();
    for (Map.Entry<String, ServerIdProofs> sIdProof : report.getSIdProofsMap().entrySet()) {
      String uSigner = sIdProof.getKey();
      Map<String, ByteString> sIdProofValues = sIdProof.getValue().getSIdProofsValuesMap();
      byte[] sProofHash = Crypto.hash(Bytes.concat(proof.toByteArray(), uSigner.getBytes()));

      for (Map.Entry<String, ByteString> sIdProofValue : sIdProofValues.entrySet()) {
        String sSigner = sIdProofValue.getKey();
        if (!sCrypto.isServer(sSigner)) {
          sIdProofValues.remove(sSigner);
          continue;
        }
        byte[] sSignedProof = sIdProofValue.getValue().toByteArray();
        byte[] sProofUnsigned = this.sCrypto.unsignPayload(sSigner, sSignedProof);
        if (!Arrays.equals(sProofUnsigned, sProofHash)) sIdProofValues.remove(sSigner);
      }
    }

    if (report.getSIdProofsMap().size() == 0) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, INVALID_ARGUMENT, "No Valid Signatures!", nonce);
    }

    ADEBInstance instance =
        this.adebInstanceManager.getInstance(proof.getUname(), proof.getEpoch(), nonce);
    if (instance != null) {
      synchronized (instance) {
        if (instance.addReady(
            req.getHeader().getSName(), uReport, this.unwrapSIdProofs(report.getSIdProofsMap()))) {
          ADEBMetadata metadata = instance.getMetadata(uReport);
          if (!instance.hasSentReady(this.mySName)
              && metadata.readyCounter() > this.nByzantineServers) {
            // Amplification
            this.sendReady(uReport, instance, metadata);
          }
          if (!instance.hasDelivered() && metadata.readyCounter() >= this.byzantineQuorum) {
            this.adebInstanceManager.deliver(proof.getUname(), proof.getEpoch(), uReport);
          }
        }
      }
    }
    byte[] hmac = Crypto.hmac(session.getSecKey(), nonce.toByteArray());
    resObs.onNext(ReadyRep.newBuilder().setHmac(ByteString.copyFrom(hmac)).build());
    resObs.onCompleted();
  }

  private void sendReady(UserReport uReport, ADEBInstance instance, ADEBMetadata metadata) {
    instance.addReady(this.mySName, uReport, this.generateSIdProofs(uReport));
    Report report =
        Report.newBuilder()
            .setUReport(uReport)
            .putAllSIdProofs(this.wrapSIdProofs(metadata.getSIdProofs()))
            .build();
    this.adebFrontend.ready(report);
  }

  private Map<String, Map<String, ByteString>> generateSIdProofs(UserReport uReport) {
    return uReport.getUIdProofsMap().keySet().stream()
        .collect(
            Collectors.toMap(
                uSigner -> uSigner,
                uSigner ->
                    Collections.singletonMap(
                        this.mySName,
                        ByteString.copyFrom(
                            this.sCrypto.signPayload(
                                Bytes.concat(
                                    uReport.getProof().toByteArray(), uSigner.getBytes()))))));
  }

  private Map<String, ServerIdProofs> wrapSIdProofs(
      Map<String, Map<String, ByteString>> sIdProofs) {
    return sIdProofs.entrySet().stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                e -> ServerIdProofs.newBuilder().putAllSIdProofsValues(e.getValue()).build()));
  }

  private Map<String, Map<String, ByteString>> unwrapSIdProofs(
      Map<String, ServerIdProofs> sIdProofs) {
    return sIdProofs.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getSIdProofsValuesMap()));
  }
}
