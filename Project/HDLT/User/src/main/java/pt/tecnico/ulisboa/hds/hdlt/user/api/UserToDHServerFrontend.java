package pt.tecnico.ulisboa.hds.hdlt.user.api;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesGrpc;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesGrpc.DHServicesBlockingStub;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesOuterClass.DHRep;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesOuterClass.DHReq;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesOuterClass.DHReqPayload;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesOuterClass.Header;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.lib.error.AssertError;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.user.session.Session;

import javax.crypto.SecretKey;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import java.math.BigInteger;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.grpc.Status.Code.*;
import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.sleep;

public class UserToDHServerFrontend {

  private final int callTimeout;
  private final int maxNRetries;
  private final String uname;
  private final UserCrypto uCrypto;
  private final List<ManagedChannel> channels;
  private final Map<String, DHServicesBlockingStub> stubs;

  public UserToDHServerFrontend(
      String uname,
      UserCrypto uCrypto,
      Map<String, String> sURLs,
      int callTimeout,
      int maxNRetries) {
    this.uname = uname;
    this.uCrypto = uCrypto;
    this.callTimeout = callTimeout;
    this.maxNRetries = maxNRetries;
    this.channels = new ArrayList<>();
    this.stubs = new HashMap<>();
    for (Map.Entry<String, String> sURL : sURLs.entrySet()) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(sURL.getValue()).usePlaintext().build();
      this.channels.add(channel);
      this.stubs.put(sURL.getKey(), DHServicesGrpc.newBlockingStub(channel));
    }
  }

  public Session dH(String sName, int nRetries) {
    Session session = this.uCrypto.getSession(sName);
    if (session != null) return session;

    KeyPair kPair;
    try {
      kPair = Crypto.doDHFirstPhase(null);
    } catch (AssertError e) {
      throw new UserRuntimeException("Invalid Crypto Arguments!");
    }
    DHPublicKey dhPubKey = (DHPublicKey) kPair.getPublic();
    DHParameterSpec dhParamSpec = dhPubKey.getParams();
    BigInteger uNonce = Crypto.generateRandomNonce();

    Header header =
        Header.newBuilder()
            .setName(this.uname)
            .setUNonce(ByteString.copyFrom(uNonce.toByteArray()))
            .build();

    DHReqPayload payload =
        DHReqPayload.newBuilder()
            .setP(ByteString.copyFrom(dhParamSpec.getP().toByteArray()))
            .setG(ByteString.copyFrom(dhParamSpec.getG().toByteArray()))
            .setY(ByteString.copyFrom(dhPubKey.getY().toByteArray()))
            .build();

    ByteString signature =
        ByteString.copyFrom(
            this.uCrypto.signPayload(
                Bytes.concat(header.toByteArray(), payload.toByteArray(), uNonce.toByteArray())));

    try {
      DHRep reply =
          this.stubs
              .get(sName)
              .withDeadlineAfter(callTimeout, TimeUnit.SECONDS)
              .dH(
                  DHReq.newBuilder()
                      .setHeader(header)
                      .setPayload(payload)
                      .setSignature(signature)
                      .build());
      return this.onDHSuccess(sName, uNonce, dhParamSpec, kPair, reply, nRetries);
    } catch (StatusRuntimeException e) {
      if (Thread.currentThread().isInterrupted()) throw new UserRuntimeException(e.getCause());
      return this.onDHFailure(sName, uNonce, e, nRetries);
    }
  }

  private Session onDHSuccess(
      String sName,
      BigInteger uNonce,
      DHParameterSpec dhParamSpec,
      KeyPair kPair,
      DHRep rep,
      int nRetries) {
    try {
      this.uCrypto.checkAuthSignature(
          sName,
          rep.getSignature().toByteArray(),
          Bytes.concat(rep.getPayload().toByteArray(), uNonce.toByteArray()));
    } catch (UserRuntimeException e) {
      if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.dH(sName, nRetries + 1);
    }

    BigInteger yServer = new BigInteger(1, rep.getPayload().getY().toByteArray());
    BigInteger sNonce = new BigInteger(1, rep.getPayload().getSNonce().toByteArray());
    SecretKey secKey;
    try {
      secKey = Crypto.doDHSecondPhase(dhParamSpec, kPair, yServer);
    } catch (AssertError e) {
      if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.dH(sName, nRetries + 1);
    }
    return this.uCrypto.newSession(sName, secKey, sNonce);
  }

  private Session onDHFailure(
      String sName, BigInteger uNonce, StatusRuntimeException e, int nRetries) {
    if (nRetries >= maxNRetries) throw new UserRuntimeException(e.getMessage());
    if (this.isToRetry(e)) {
      System.out.printf("%s%nRetrying (...)%n", e.getMessage());
      sleep(1000);
      return this.dH(sName, nRetries + 1);
    }
    try {
      this.uCrypto.checkErrorAuth(sName, e, uNonce);
    } catch (UserRuntimeException e1) {
      System.out.printf("Exception Validation Failed: %s%nRetrying (...)%n", e1.getMessage());
      sleep(1000);
      return this.dH(sName, nRetries + 1);
    }
    throw new UserRuntimeException(e.getMessage());
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
