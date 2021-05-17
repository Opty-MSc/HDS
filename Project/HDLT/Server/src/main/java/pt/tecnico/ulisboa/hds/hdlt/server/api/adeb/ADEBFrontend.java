package pt.tecnico.ulisboa.hds.hdlt.server.api.adeb;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Report;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.UserReport;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesGrpc;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesGrpc.ADEBServicesBlockingStub;
import pt.tecnico.ulisboa.hds.hdlt.contract.ss.ADEBServicesOuterClass.*;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.server.api.DHFrontend;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ServerCrypto;
import pt.tecnico.ulisboa.hds.hdlt.server.error.ServerStatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.server.session.Session;

import javax.crypto.spec.IvParameterSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.grpc.Status.Code.*;
import static io.grpc.Status.RESOURCE_EXHAUSTED;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.sleep;

public class ADEBFrontend {

  private final int callTimeout;
  private final int maxNRetries;
  private final String mySName;
  private final ServerCrypto sCrypto;
  private final DHFrontend dhFrontend;
  private final List<ManagedChannel> channels;
  private final Map<String, ADEBServicesBlockingStub> stubs;

  public ADEBFrontend(
      String mySName,
      ServerCrypto sCrypto,
      Map<String, String> sURLs,
      DHFrontend dhFrontend,
      int callTimeout,
      int maxNRetries) {
    this.mySName = mySName;
    this.sCrypto = sCrypto;
    this.dhFrontend = dhFrontend;
    this.callTimeout = callTimeout;
    this.maxNRetries = maxNRetries;
    this.channels = new ArrayList<>();
    this.stubs = new HashMap<>();
    for (Map.Entry<String, String> sURL : sURLs.entrySet()) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(sURL.getValue()).usePlaintext().build();
      this.channels.add(channel);
      this.stubs.put(sURL.getKey(), ADEBServicesGrpc.newBlockingStub(channel));
    }
  }

  public void echo(UserReport uReport) {
    this.stubs.keySet().forEach(sName -> new Thread(() -> this.echo(sName, uReport, 0)).start());
  }

  public void echo(String sName, UserReport uReport, int nRetries) {
    synchronized (this.stubs.get(sName)) {
      Session session = this.dhFrontend.dH(sName, 0);
      BigInteger nonce = session.newNonce();
      IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
      Header header = this.generateHeader(nonce, iv);

      byte[] cipheredUserReport =
          Crypto.cipherBytesAES(session.getSecKey(), iv, uReport.toByteArray());
      byte[] hmac =
          Crypto.hmac(
              session.getSecKey(), Bytes.concat(header.toByteArray(), uReport.toByteArray()));

      try {
        EchoRep reply =
            this.stubs
                .get(sName)
                .withDeadlineAfter(callTimeout, TimeUnit.SECONDS)
                .echo(
                    EchoReq.newBuilder()
                        .setHeader(header)
                        .setCipheredUserReport(ByteString.copyFrom(cipheredUserReport))
                        .setHmac(ByteString.copyFrom(hmac))
                        .build());
        this.onEchoSuccess(sName, session, nonce, uReport, reply, nRetries);
      } catch (StatusRuntimeException e) {
        this.onEchoFailure(sName, session, nonce, uReport, e, nRetries);
      }
    }
  }

  private void onEchoSuccess(
      String sName,
      Session session,
      BigInteger nonce,
      UserReport uReport,
      EchoRep reply,
      int nRetries) {
    try {
      this.sCrypto.checkAuthHmac(
          session, reply.getHmac().toByteArray(), nonce.toByteArray(), nonce);
    } catch (ServerStatusRuntimeException e) {
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      this.echo(sName, uReport, nRetries + 1);
    }
  }

  private void onEchoFailure(
      String sName,
      Session session,
      BigInteger nonce,
      UserReport uReport,
      StatusRuntimeException e,
      int nRetries) {
    if (nRetries >= maxNRetries)
      throw new ServerStatusRuntimeException(RESOURCE_EXHAUSTED, e.getMessage());
    if (this.isToRetry(e)) {
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      this.echo(sName, uReport, nRetries + 1);
      return;
    }
    try {
      this.sCrypto.checkErrorAuth(sName, e, nonce);
    } catch (ServerStatusRuntimeException e1) {
      System.out.printf("Exception Validation Failed: %s%nRetrying (...)%n", e1.getMessage());
      sleep(1000);
      this.echo(sName, uReport, nRetries + 1);
      return;
    }
    if (e.getStatus().getCode() == UNAUTHENTICATED) {
      session.invalidate();
      this.echo(sName, uReport, nRetries + 1);
      return;
    }
    throw new ServerStatusRuntimeException(e.getStatus(), e.getMessage());
  }

  public void ready(Report report) {
    this.stubs.keySet().forEach(sName -> new Thread(() -> this.ready(sName, report, 0)).start());
  }

  public void ready(String sName, Report report, int nRetries) {
    synchronized (this.stubs.get(sName)) {
      Session session = this.dhFrontend.dH(sName, 0);
      BigInteger nonce = session.newNonce();
      IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
      Header header = this.generateHeader(nonce, iv);

      byte[] cipheredReport = Crypto.cipherBytesAES(session.getSecKey(), iv, report.toByteArray());
      byte[] hmac =
          Crypto.hmac(
              session.getSecKey(), Bytes.concat(header.toByteArray(), report.toByteArray()));

      try {
        ReadyRep reply =
            this.stubs
                .get(sName)
                .withDeadlineAfter(callTimeout, TimeUnit.SECONDS)
                .ready(
                    ReadyReq.newBuilder()
                        .setHeader(header)
                        .setCipheredReport(ByteString.copyFrom(cipheredReport))
                        .setHmac(ByteString.copyFrom(hmac))
                        .build());
        this.onReadySuccess(sName, session, nonce, report, reply, nRetries);
      } catch (StatusRuntimeException e) {
        this.onReadyFailure(sName, session, nonce, report, e, nRetries);
      }
    }
  }

  private void onReadySuccess(
      String sName,
      Session session,
      BigInteger nonce,
      Report report,
      ReadyRep reply,
      int nRetries) {
    try {
      this.sCrypto.checkAuthHmac(
          session, reply.getHmac().toByteArray(), nonce.toByteArray(), nonce);
    } catch (ServerStatusRuntimeException e) {
      if (nRetries >= maxNRetries)
        throw new ServerStatusRuntimeException(RESOURCE_EXHAUSTED, e.getMessage());
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      this.ready(sName, report, nRetries + 1);
    }
  }

  private void onReadyFailure(
      String sName,
      Session session,
      BigInteger nonce,
      Report report,
      StatusRuntimeException e,
      int nRetries) {
    if (nRetries >= maxNRetries)
      throw new ServerStatusRuntimeException(RESOURCE_EXHAUSTED, e.getMessage());
    if (this.isToRetry(e)) {
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      this.ready(sName, report, nRetries + 1);
      return;
    }
    try {
      this.sCrypto.checkErrorAuth(sName, e, nonce);
    } catch (ServerStatusRuntimeException e1) {
      System.out.printf("Exception Validation Failed: %s%nRetrying (...)%n", e1.getMessage());
      sleep(1000);
      this.ready(sName, report, nRetries + 1);
      return;
    }
    if (e.getStatus().getCode() == UNAUTHENTICATED) {
      session.invalidate();
      this.ready(sName, report, nRetries + 1);
      return;
    }
    throw new ServerStatusRuntimeException(e.getStatus(), e.getMessage());
  }

  private Header generateHeader(BigInteger nonce, IvParameterSpec iv) {
    return Header.newBuilder()
        .setSName(this.mySName)
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

  public void shutdown() {
    this.channels.forEach(ManagedChannel::shutdown);
  }
}
