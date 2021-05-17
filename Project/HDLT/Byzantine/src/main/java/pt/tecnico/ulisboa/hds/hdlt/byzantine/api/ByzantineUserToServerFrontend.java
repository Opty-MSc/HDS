package pt.tecnico.ulisboa.hds.hdlt.byzantine.api;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesGrpc;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesGrpc.ClientServerServicesBlockingStub;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.*;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserCrypto;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserToDHServerFrontend;
import pt.tecnico.ulisboa.hds.hdlt.user.session.Session;

import javax.crypto.spec.IvParameterSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ByzantineUserToServerFrontend {

  private final String uname;
  private final UserToDHServerFrontend dhFrontend;
  private final UserCrypto uCrypto;
  private final List<ManagedChannel> channels;
  private final Map<String, ClientServerServicesBlockingStub> stubs;

  public ByzantineUserToServerFrontend(
      String uname,
      UserCrypto uCrypto,
      Map<String, String> sURLs,
      UserToDHServerFrontend dhFrontend) {
    this.uname = uname;
    this.uCrypto = uCrypto;
    this.dhFrontend = dhFrontend;
    this.channels = new ArrayList<>();
    this.stubs = new HashMap<>();
    for (Map.Entry<String, String> sURL : sURLs.entrySet()) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(sURL.getValue()).usePlaintext().build();
      this.channels.add(channel);
      this.stubs.put(sURL.getKey(), ClientServerServicesGrpc.newBlockingStub(channel));
    }
  }

  public void submitULReportNoFreshness(String sName, UserReport uReport) {
    Session session = this.dhFrontend.dH(sName, 0);
    // Old Nonce, not Nonce + 1
    BigInteger nonce = session.getNonce();
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

    SubmitULReportReq req =
        SubmitULReportReq.newBuilder()
            .setHeader(header)
            .setPow(pow)
            .setCipheredUserReport(ByteString.copyFrom(cipheredUserReport))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).submitULReport(req);
  }

  public void submitULReportNoValidHMAC(String sName, UserReport uReport) {
    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);
    byte[] cipheredUserReport =
        Crypto.cipherBytesAES(session.getSecKey(), iv, uReport.toByteArray());
    long pow =
        this.uCrypto.generateProofOfWork(Bytes.concat(header.toByteArray(), uReport.toByteArray()));
    // Invalid HMAC (Added Nonce 2 Times to the hmac)
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(
                header.toByteArray(),
                nonce.toByteArray(),
                Longs.toByteArray(pow),
                uReport.toByteArray()));

    SubmitULReportReq req =
        SubmitULReportReq.newBuilder()
            .setHeader(header)
            .setPow(pow)
            .setCipheredUserReport(ByteString.copyFrom(cipheredUserReport))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).submitULReport(req);
  }

  public void submitULReportInvalidPow(String sName, UserReport uReport) {
    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    byte[] cipheredUserReport =
        Crypto.cipherBytesAES(session.getSecKey(), iv, uReport.toByteArray());
    long pow = 0;
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(header.toByteArray(), Longs.toByteArray(pow), uReport.toByteArray()));

    SubmitULReportReq req =
        SubmitULReportReq.newBuilder()
            .setHeader(header)
            .setPow(pow)
            .setCipheredUserReport(ByteString.copyFrom(cipheredUserReport))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).submitULReport(req);
  }

  public void obtainULNoFreshness(String sName, Integer epoch) {
    Session session = this.dhFrontend.dH(sName, 0);
    // Old Nonce, not Nonce + 1
    BigInteger nonce = session.getNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    ObtainULReqPayload reqPayload =
        ObtainULReqPayload.newBuilder().setUname(this.uname).setEpoch(epoch).build();
    byte[] cipheredPayload =
        Crypto.cipherBytesAES(session.getSecKey(), iv, reqPayload.toByteArray());
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(), Bytes.concat(header.toByteArray(), reqPayload.toByteArray()));
    ObtainULReq req =
        ObtainULReq.newBuilder()
            .setHeader(header)
            .setCipheredPayload(ByteString.copyFrom(cipheredPayload))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).obtainUL(req);
  }

  public void obtainULNoValidHMAC(String sName, Integer epoch) {
    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    ObtainULReqPayload reqPayload =
        ObtainULReqPayload.newBuilder().setUname(this.uname).setEpoch(epoch).build();
    byte[] cipheredPayload =
        Crypto.cipherBytesAES(session.getSecKey(), iv, reqPayload.toByteArray());
    // Invalid HMAC (Added Nonce 2 Times to the hmac)
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(header.toByteArray(), nonce.toByteArray(), reqPayload.toByteArray()));
    ObtainULReq req =
        ObtainULReq.newBuilder()
            .setHeader(header)
            .setCipheredPayload(ByteString.copyFrom(cipheredPayload))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).obtainUL(req);
  }

  public void requestMyProofsNoFreshness(String sName, List<Integer> epochs) {
    Session session = this.dhFrontend.dH(sName, 0);
    // Old Nonce, not Nonce + 1
    BigInteger nonce = session.getNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    RequestMyProofsReqPayload reqPayload =
        RequestMyProofsReqPayload.newBuilder().addAllEpochs(epochs).build();

    byte[] cipheredPayload =
        Crypto.cipherBytesAES(session.getSecKey(), iv, reqPayload.toByteArray());

    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(), Bytes.concat(header.toByteArray(), reqPayload.toByteArray()));

    RequestMyProofsReq req =
        RequestMyProofsReq.newBuilder()
            .setHeader(header)
            .setCipheredPayload(ByteString.copyFrom(cipheredPayload))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).requestMyProofs(req);
  }

  public void requestMyProofsNoValidHMAC(String sName, List<Integer> epochs) {
    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    RequestMyProofsReqPayload reqPayload =
        RequestMyProofsReqPayload.newBuilder().addAllEpochs(epochs).build();

    byte[] cipheredPayload =
        Crypto.cipherBytesAES(session.getSecKey(), iv, reqPayload.toByteArray());

    // Invalid HMAC (Added Nonce 2 Times to the hmac)
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(header.toByteArray(), nonce.toByteArray(), reqPayload.toByteArray()));

    RequestMyProofsReq req =
        RequestMyProofsReq.newBuilder()
            .setHeader(header)
            .setCipheredPayload(ByteString.copyFrom(cipheredPayload))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).requestMyProofs(req);
  }

  public void obtainULWriteBackInvalidPow(String sName, Report report) {
    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);

    byte[] cipheredReport = Crypto.cipherBytesAES(session.getSecKey(), iv, report.toByteArray());
    long pow = 0;
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(header.toByteArray(), Longs.toByteArray(pow), report.toByteArray()));

    ObtainULWriteBackReq req =
        ObtainULWriteBackReq.newBuilder()
            .setHeader(header)
            .setPow(pow)
            .setCipheredReport(ByteString.copyFrom(cipheredReport))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).obtainULWriteBack(req);
  }

  public void submitULReportInvalidSession(Session session, String sName, UserReport uReport) {
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

    SubmitULReportReq req =
        SubmitULReportReq.newBuilder()
            .setHeader(header)
            .setPow(pow)
            .setCipheredUserReport(ByteString.copyFrom(cipheredUserReport))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).submitULReport(req);
  }

  public void obtainULInvalidSession(Session session, String sName, Integer epoch) {
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

    ObtainULReq req =
        ObtainULReq.newBuilder()
            .setHeader(header)
            .setCipheredPayload(ByteString.copyFrom(cipheredPayload))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).obtainUL(req);
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
