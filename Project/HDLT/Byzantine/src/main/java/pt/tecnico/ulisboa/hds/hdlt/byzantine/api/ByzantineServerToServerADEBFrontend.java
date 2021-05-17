package pt.tecnico.ulisboa.hds.hdlt.byzantine.api;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Report;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.UserReport;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesGrpc;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesGrpc.ADEBServicesBlockingStub;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesOuterClass.EchoReq;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesOuterClass.Header;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesOuterClass.ReadyReq;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.server.api.DHFrontend;
import pt.tecnico.ulisboa.hds.hdlt.server.session.Session;

import javax.crypto.spec.IvParameterSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ByzantineServerToServerADEBFrontend {

  private final String mySName;
  private final DHFrontend dhFrontend;
  private final List<ManagedChannel> channels;
  private final Map<String, ADEBServicesBlockingStub> stubs;

  public ByzantineServerToServerADEBFrontend(
      String mySName, Map<String, String> sURLs, DHFrontend dhFrontend) {
    this.mySName = mySName;
    this.dhFrontend = dhFrontend;
    this.channels = new ArrayList<>();
    this.stubs = new HashMap<>();
    for (Map.Entry<String, String> sURL : sURLs.entrySet()) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(sURL.getValue()).usePlaintext().build();
      this.channels.add(channel);
      this.stubs.put(sURL.getKey(), ADEBServicesGrpc.newBlockingStub(channel));
    }
  }

  public void echoNoFreshness(String sName, UserReport uReport) {
    Session session = this.dhFrontend.dH(sName, 0);
    // Old Nonce, Not Nonce + 1
    BigInteger nonce = session.getNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);
    byte[] cipheredUserReport =
        Crypto.cipherBytesAES(session.getSecKey(), iv, uReport.toByteArray());
    byte[] hmac =
        Crypto.hmac(session.getSecKey(), Bytes.concat(header.toByteArray(), uReport.toByteArray()));
    EchoReq echoReq =
        EchoReq.newBuilder()
            .setHeader(header)
            .setCipheredUserReport(ByteString.copyFrom(cipheredUserReport))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).echo(echoReq);
  }

  public void echoNoValidHMAC(String sName, UserReport uReport) {
    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);
    byte[] cipheredUserReport =
        Crypto.cipherBytesAES(session.getSecKey(), iv, uReport.toByteArray());

    // Invalid HMAC (Added Nonce 2 Times to the hmac)
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(header.toByteArray(), nonce.toByteArray(), uReport.toByteArray()));

    EchoReq echoReq =
        EchoReq.newBuilder()
            .setHeader(header)
            .setCipheredUserReport(ByteString.copyFrom(cipheredUserReport))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).echo(echoReq);
  }

  public void echoWithInvalidSession(Session session, String sName, UserReport uReport) {
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);
    byte[] cipheredUserReport =
        Crypto.cipherBytesAES(session.getSecKey(), iv, uReport.toByteArray());
    byte[] hmac =
        Crypto.hmac(session.getSecKey(), Bytes.concat(header.toByteArray(), uReport.toByteArray()));
    EchoReq echoReq =
        EchoReq.newBuilder()
            .setHeader(header)
            .setCipheredUserReport(ByteString.copyFrom(cipheredUserReport))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).echo(echoReq);
  }

  public void readyNoFreshness(String sName, Report report) {
    Session session = this.dhFrontend.dH(sName, 0);
    // Old Nonce, Not Nonce + 1
    BigInteger nonce = session.getNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);
    byte[] cipheredReport = Crypto.cipherBytesAES(session.getSecKey(), iv, report.toByteArray());
    byte[] hmac =
        Crypto.hmac(session.getSecKey(), Bytes.concat(header.toByteArray(), report.toByteArray()));
    ReadyReq readyReq =
        ReadyReq.newBuilder()
            .setHeader(header)
            .setCipheredReport(ByteString.copyFrom(cipheredReport))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).ready(readyReq);
  }

  public void readyNoValidHMAC(String sName, Report report) {
    Session session = this.dhFrontend.dH(sName, 0);
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);
    byte[] cipheredReport = Crypto.cipherBytesAES(session.getSecKey(), iv, report.toByteArray());

    // Invalid HMAC (Added Nonce 2 Times to the hmac)
    byte[] hmac =
        Crypto.hmac(
            session.getSecKey(),
            Bytes.concat(header.toByteArray(), nonce.toByteArray(), report.toByteArray()));

    ReadyReq readyReq =
        ReadyReq.newBuilder()
            .setHeader(header)
            .setCipheredReport(ByteString.copyFrom(cipheredReport))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).ready(readyReq);
  }

  public void readyWithInvalidSession(Session session, String sName, Report report) {
    BigInteger nonce = session.newNonce();
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    Header header = this.generateHeader(nonce, iv);
    byte[] cipheredReport = Crypto.cipherBytesAES(session.getSecKey(), iv, report.toByteArray());
    byte[] hmac =
        Crypto.hmac(session.getSecKey(), Bytes.concat(header.toByteArray(), report.toByteArray()));
    ReadyReq readyReq =
        ReadyReq.newBuilder()
            .setHeader(header)
            .setCipheredReport(ByteString.copyFrom(cipheredReport))
            .setHmac(ByteString.copyFrom(hmac))
            .build();
    this.stubs.get(sName).ready(readyReq);
  }

  private Header generateHeader(BigInteger nonce, IvParameterSpec iv) {
    return Header.newBuilder()
        .setSName(this.mySName)
        .setNonce(ByteString.copyFrom(nonce.toByteArray()))
        .setIv(ByteString.copyFrom(iv.getIV()))
        .build();
  }

  public void shutdown() {
    this.channels.forEach(ManagedChannel::shutdown);
  }
}
