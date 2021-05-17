package pt.tecnico.ulisboa.hds.hdlt.server.api;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesGrpc.ClientServerServicesImplBase;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.*;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.server.api.adeb.ADEBFrontend;
import pt.tecnico.ulisboa.hds.hdlt.server.api.adeb.ADEBInstance;
import pt.tecnico.ulisboa.hds.hdlt.server.api.adeb.ADEBInstanceManager;
import pt.tecnico.ulisboa.hds.hdlt.server.error.ServerStatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.server.repository.DBManager;
import pt.tecnico.ulisboa.hds.hdlt.server.repository.domain.DBAuthProof;
import pt.tecnico.ulisboa.hds.hdlt.server.repository.domain.DBProof;
import pt.tecnico.ulisboa.hds.hdlt.server.repository.domain.DBReport;
import pt.tecnico.ulisboa.hds.hdlt.server.session.Session;

import javax.crypto.spec.IvParameterSpec;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.grpc.Status.*;
import static pt.tecnico.ulisboa.hds.hdlt.server.api.CommonServices.*;

public class ClientServerServicesImpl extends ClientServerServicesImplBase {

  private final ServerCrypto sCrypto;
  private final DBManager db;
  private final ADEBInstanceManager adebInstanceManager;
  private final ADEBFrontend adebFrontend;
  private final int nByzantineUsers;
  private final int byzantineQuorum;

  public ClientServerServicesImpl(
      ServerCrypto sCrypto,
      DBManager db,
      ADEBInstanceManager adebInstanceManager,
      ADEBFrontend adebFrontend,
      int nByzantineUsers,
      int nByzantineServers) {
    this.sCrypto = sCrypto;
    this.db = db;
    this.adebInstanceManager = adebInstanceManager;
    this.adebFrontend = adebFrontend;
    this.nByzantineUsers = nByzantineUsers;
    this.byzantineQuorum = 2 * nByzantineServers + 1;
  }

  @Override
  public void submitULReport(SubmitULReportReq req, StreamObserver<SubmitULReportRep> resObs) {

    BigInteger nonce = new BigInteger(1, req.getHeader().getNonce().toByteArray());
    if (!this.sCrypto.isUser(req.getHeader().getUname())) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, PERMISSION_DENIED, "Permission Denied!", nonce);
    }

    Session session = this.sCrypto.getSessionAsServer(req.getHeader().getUname());
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
        Bytes.concat(req.getHeader().toByteArray(), Longs.toByteArray(req.getPow()), payload),
        nonce);
    if (!this.sCrypto.verifyProofOfWork(
        Bytes.concat(req.getHeader().toByteArray(), payload), req.getPow())) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, INVALID_ARGUMENT, "Invalid Proof of Work!", nonce);
    }

    UserReport uReport;
    try {
      uReport = UserReport.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, INVALID_ARGUMENT, "Invalid Payload!", nonce);
    }
    Proof proof = uReport.getProof();
    if (!proof.getUname().equals(req.getHeader().getUname())) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, PERMISSION_DENIED, "Permission Denied!", nonce);
    }

    verifyReport(
        this.sCrypto,
        this.nByzantineUsers,
        this.byzantineQuorum,
        nonce,
        Report.newBuilder().setUReport(uReport).build(),
        false);

    ADEBInstance instance =
        this.adebInstanceManager.getInstance(proof.getUname(), proof.getEpoch(), nonce);
    if (instance == null) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, ALREADY_EXISTS, "Report Already Exists!", nonce);
    }
    synchronized (instance) {
      if (instance.isProcessing()) {
        throw new ServerStatusRuntimeException(
            this.sCrypto, PERMISSION_DENIED, "Report Already Being Written!", nonce);
      }
      instance.process();
    }
    if (this.nByzantineUsers > 0) {
      this.adebFrontend.echo(uReport);
      instance.awaitDelivery();
    } else {
      this.adebInstanceManager.deliver(proof.getUname(), proof.getEpoch(), uReport);
    }
    if (instance.getThrowable() != null) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, INTERNAL, instance.getThrowable().getMessage(), nonce);
    }
    byte[] hmac = Crypto.hmac(session.getSecKey(), nonce.toByteArray());
    resObs.onNext(SubmitULReportRep.newBuilder().setHmac(ByteString.copyFrom(hmac)).build());
    resObs.onCompleted();
  }

  @Override
  public void obtainUL(ObtainULReq req, StreamObserver<ObtainULRep> resObs) {

    BigInteger nonce = new BigInteger(1, req.getHeader().getNonce().toByteArray());
    if (!this.sCrypto.isUser(req.getHeader().getUname())
        && !req.getHeader().getUname().equals("HA")) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, PERMISSION_DENIED, "Permission Denied!", nonce);
    }

    Session session = this.sCrypto.getSessionAsServer(req.getHeader().getUname());
    verifySession(this.sCrypto, session, nonce);

    IvParameterSpec iv = new IvParameterSpec(req.getHeader().getIv().toByteArray());
    byte[] payload;
    try {
      payload =
          Crypto.decipherBytesAES(session.getSecKey(), iv, req.getCipheredPayload().toByteArray());
    } catch (AssertionError e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, UNAUTHENTICATED, "Invalid Session!", nonce);
    }

    this.sCrypto.checkAuthHmac(
        session,
        req.getHmac().toByteArray(),
        Bytes.concat(req.getHeader().toByteArray(), payload),
        nonce);

    ObtainULReqPayload reqPayload;
    try {
      reqPayload = ObtainULReqPayload.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, INVALID_ARGUMENT, "Invalid Payload!", nonce);
    }

    if (!reqPayload.getUname().equals(req.getHeader().getUname())
        && !req.getHeader().getUname().equals("HA")) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, PERMISSION_DENIED, "Permission Denied!", nonce);
    }

    DBReport dbReport = this.db.getReport(reqPayload.getUname(), reqPayload.getEpoch(), nonce);
    ObtainULRepPayload repPayload =
        ObtainULRepPayload.newBuilder()
            .setX(dbReport.getLocation().getX())
            .setY(dbReport.getLocation().getY())
            .putAllUIdProofs(toByteString(dbReport.getUserIdProofs()))
            .putAllSIdProofs(
                dbReport.getServerIdProofs().entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            Map.Entry::getKey,
                            e ->
                                ServerIdProofs.newBuilder()
                                    .putAllSIdProofsValues(toByteString(e.getValue()))
                                    .build())))
            .build();

    iv = new IvParameterSpec(Crypto.generateRandomIV());
    byte[] cipheredPayload =
        Crypto.cipherBytesAES(session.getSecKey(), iv, repPayload.toByteArray());
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(repPayload.toByteArray(), nonce.toByteArray(), iv.getIV()));

    resObs.onNext(
        ObtainULRep.newBuilder()
            .setIv(ByteString.copyFrom(iv.getIV()))
            .setCipheredPayload(ByteString.copyFrom(cipheredPayload))
            .setHmac(ByteString.copyFrom(hmac))
            .build());
    resObs.onCompleted();
  }

  @Override
  public void obtainUAtL(ObtainUAtLReq req, StreamObserver<ObtainUAtLRep> resObs) {

    BigInteger nonce = new BigInteger(1, req.getHeader().getNonce().toByteArray());
    if (!req.getHeader().getUname().equals("HA")) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, PERMISSION_DENIED, "Permission Denied!", nonce);
    }

    Session session = this.sCrypto.getSessionAsServer(req.getHeader().getUname());
    verifySession(this.sCrypto, session, nonce);

    IvParameterSpec iv = new IvParameterSpec(req.getHeader().getIv().toByteArray());
    byte[] payload;
    try {
      payload =
          Crypto.decipherBytesAES(session.getSecKey(), iv, req.getCipheredPayload().toByteArray());
    } catch (AssertionError e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, UNAUTHENTICATED, "Invalid Session!", nonce);
    }

    this.sCrypto.checkAuthHmac(
        session,
        req.getHmac().toByteArray(),
        Bytes.concat(req.getHeader().toByteArray(), payload),
        nonce);

    ObtainUAtLReqPayload reqPayload;
    try {
      reqPayload = ObtainUAtLReqPayload.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, INVALID_ARGUMENT, "Invalid Payload!", nonce);
    }

    Map<String, DBReport> reports =
        this.db.getReportsAtLocation(
            reqPayload.getEpoch(), new Location(reqPayload.getX(), reqPayload.getY()), nonce);

    ObtainUAtLRepPayload.Builder repPayloadBuilder = ObtainUAtLRepPayload.newBuilder();
    for (Map.Entry<String, DBReport> reportEntry : reports.entrySet()) {
      String uname = reportEntry.getKey();
      DBReport report = reportEntry.getValue();
      repPayloadBuilder.addReports(
          Report.newBuilder()
              .setUReport(
                  UserReport.newBuilder()
                      .setProof(
                          Proof.newBuilder()
                              .setUname(uname)
                              .setEpoch(reqPayload.getEpoch())
                              .setX(report.getLocation().getX())
                              .setY(report.getLocation().getY())
                              .build())
                      .putAllUIdProofs(toByteString(report.getUserIdProofs())))
              .putAllSIdProofs(
                  report.getServerIdProofs().entrySet().stream()
                      .collect(
                          Collectors.toMap(
                              Map.Entry::getKey,
                              e ->
                                  ServerIdProofs.newBuilder()
                                      .putAllSIdProofsValues(toByteString(e.getValue()))
                                      .build())))
              .build());
    }
    ObtainUAtLRepPayload repPayload = repPayloadBuilder.build();

    iv = new IvParameterSpec(Crypto.generateRandomIV());
    byte[] cipheredPayload =
        Crypto.cipherBytesAES(session.getSecKey(), iv, repPayload.toByteArray());
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(repPayload.toByteArray(), nonce.toByteArray(), iv.getIV()));

    resObs.onNext(
        ObtainUAtLRep.newBuilder()
            .setIv(ByteString.copyFrom(iv.getIV()))
            .setCipheredPayload(ByteString.copyFrom(cipheredPayload))
            .setHmac(ByteString.copyFrom(hmac))
            .build());
    resObs.onCompleted();
  }

  @Override
  public void requestMyProofs(RequestMyProofsReq req, StreamObserver<RequestMyProofsRep> resObs) {

    BigInteger nonce = new BigInteger(1, req.getHeader().getNonce().toByteArray());
    if (!this.sCrypto.isUser(req.getHeader().getUname())) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, PERMISSION_DENIED, "Permission Denied!", nonce);
    }

    Session session = this.sCrypto.getSessionAsServer(req.getHeader().getUname());
    verifySession(this.sCrypto, session, nonce);

    IvParameterSpec iv = new IvParameterSpec(req.getHeader().getIv().toByteArray());
    byte[] payload;
    try {
      payload =
          Crypto.decipherBytesAES(session.getSecKey(), iv, req.getCipheredPayload().toByteArray());
    } catch (AssertionError e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, UNAUTHENTICATED, "Invalid Session!", nonce);
    }

    this.sCrypto.checkAuthHmac(
        session,
        req.getHmac().toByteArray(),
        Bytes.concat(req.getHeader().toByteArray(), payload),
        nonce);

    RequestMyProofsReqPayload reqPayload;
    try {
      reqPayload = RequestMyProofsReqPayload.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, INVALID_ARGUMENT, "Invalid Payload!", nonce);
    }

    Set<DBAuthProof> dbAuthProofs =
        this.db.getLocationProofs(
            req.getHeader().getUname(), reqPayload.getEpochsList().toArray(), nonce);

    RequestMyProofsRepPayload.Builder repPayloadBuilder = RequestMyProofsRepPayload.newBuilder();
    for (DBAuthProof dbAuthProof : dbAuthProofs) {
      DBProof dbProof = dbAuthProof.getProof();
      repPayloadBuilder.addAuthProofs(
          AuthProof.newBuilder()
              .setProof(
                  Proof.newBuilder()
                      .setUname(dbProof.getUname())
                      .setEpoch(dbProof.getEpoch())
                      .setX(dbProof.getLocation().getX())
                      .setY(dbProof.getLocation().getY())
                      .build())
              .setSignedProof(ByteString.copyFrom(dbAuthProof.getUserSignedProof()))
              .setSIdProofsValues(
                  ServerIdProofs.newBuilder()
                      .putAllSIdProofsValues(toByteString(dbAuthProof.getServerIdProofs()))
                      .build())
              .build());
    }
    RequestMyProofsRepPayload repPayload = repPayloadBuilder.build();

    iv = new IvParameterSpec(Crypto.generateRandomIV());
    byte[] cipheredPayload =
        Crypto.cipherBytesAES(session.getSecKey(), iv, repPayload.toByteArray());
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(repPayload.toByteArray(), nonce.toByteArray(), iv.getIV()));

    resObs.onNext(
        RequestMyProofsRep.newBuilder()
            .setIv(ByteString.copyFrom(iv.getIV()))
            .setCipheredPayload(ByteString.copyFrom(cipheredPayload))
            .setHmac(ByteString.copyFrom(hmac))
            .build());
    resObs.onCompleted();
  }

  @Override
  public void obtainULWriteBack(
      ObtainULWriteBackReq req, StreamObserver<ObtainULWriteBackRep> resObs) {

    BigInteger nonce = new BigInteger(1, req.getHeader().getNonce().toByteArray());
    if (!this.sCrypto.isUser(req.getHeader().getUname())
        && !req.getHeader().getUname().equals("HA")) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, PERMISSION_DENIED, "Permission Denied!", nonce);
    }

    Session session = this.sCrypto.getSessionAsServer(req.getHeader().getUname());
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
        Bytes.concat(req.getHeader().toByteArray(), Longs.toByteArray(req.getPow()), payload),
        nonce);
    if (!this.sCrypto.verifyProofOfWork(
        Bytes.concat(req.getHeader().toByteArray(), payload), req.getPow())) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, INVALID_ARGUMENT, "Invalid Proof of Work!", nonce);
    }

    Report report;
    try {
      report = Report.parseFrom(payload);
    } catch (InvalidProtocolBufferException e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, INVALID_ARGUMENT, "Invalid Payload!", nonce);
    }

    Proof proof = report.getUReport().getProof();
    if (!req.getHeader().getUname().equals(proof.getUname())
        && !req.getHeader().getUname().equals("HA")) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, PERMISSION_DENIED, "Permission Denied!", nonce);
    }

    try {
      if (!this.db.hasReport(proof.getUname(), proof.getEpoch())) {
        verifyReport(this.sCrypto, this.nByzantineUsers, this.byzantineQuorum, nonce, report, true);

        this.db.addReport(
            proof.getUname(),
            proof.getEpoch(),
            new Location(proof.getX(), proof.getY()),
            toByteArray(report.getUReport().getUIdProofsMap()),
            report.getSIdProofsMap().entrySet().stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey,
                        e -> toByteArray(e.getValue().getSIdProofsValuesMap()))));
      }
    } catch (SQLException e) {
      throw new ServerStatusRuntimeException(
          this.sCrypto, Status.INTERNAL, "Database Error!", nonce);
    }

    byte[] hmac = Crypto.hmac(session.getSecKey(), nonce.toByteArray());
    resObs.onNext(ObtainULWriteBackRep.newBuilder().setHmac(ByteString.copyFrom(hmac)).build());
    resObs.onCompleted();
  }
}
