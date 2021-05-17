package pt.tecnico.ulisboa.hds.hdlt.byzantine.api;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesGrpc;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesOuterClass;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesOuterClass.DHReqPayload;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesOuterClass.Header;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.lib.error.AssertError;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ServerCrypto;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;

import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import java.math.BigInteger;
import java.security.KeyPair;

public class ByzantineServerToDHServerFrontend {

  private final String mySName;
  private final ServerCrypto sCrypto;
  private final ManagedChannel channel;
  private final DHServicesGrpc.DHServicesBlockingStub stub;

  public ByzantineServerToDHServerFrontend(String mySName, ServerCrypto sCrypto, String sURL) {
    this.mySName = mySName;
    this.sCrypto = sCrypto;
    this.channel = ManagedChannelBuilder.forTarget(sURL).usePlaintext().build();
    this.stub = DHServicesGrpc.newBlockingStub(this.channel);
  }

  public void dHNoFreshness() {
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
            .setName(this.mySName)
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
            this.sCrypto.signPayload(
                Bytes.concat(header.toByteArray(), payload.toByteArray(), uNonce.toByteArray())));

    DHServicesOuterClass.DHReq req =
        DHServicesOuterClass.DHReq.newBuilder()
            .setHeader(header)
            .setPayload(payload)
            .setSignature(signature)
            .build();
    this.stub.dH(req);
    // Double Nonce
    this.stub.dH(req);
  }

  public void dHNoValidSignature() {
    KeyPair kPair;
    try {
      kPair = Crypto.doDHFirstPhase(null);
    } catch (AssertError e) {
      throw new UserRuntimeException("Invalid Crypto Arguments!");
    }
    DHPublicKey dhPubKey = (DHPublicKey) kPair.getPublic();
    DHParameterSpec dhParamSpec = dhPubKey.getParams();
    BigInteger uNonce = Crypto.generateRandomNonce();

    DHServicesOuterClass.Header header =
        DHServicesOuterClass.Header.newBuilder()
            .setName(this.mySName)
            .setUNonce(ByteString.copyFrom(uNonce.toByteArray()))
            .build();

    DHServicesOuterClass.DHReqPayload payload =
        DHServicesOuterClass.DHReqPayload.newBuilder()
            .setP(ByteString.copyFrom(dhParamSpec.getP().toByteArray()))
            .setG(ByteString.copyFrom(dhParamSpec.getG().toByteArray()))
            .setY(ByteString.copyFrom(dhPubKey.getY().toByteArray()))
            .build();
    // Invalid Signature (Added a Nonce 2 Times to the signature)
    ByteString signature =
        ByteString.copyFrom(
            this.sCrypto.signPayload(Bytes.concat(header.toByteArray(), uNonce.toByteArray())));

    DHServicesOuterClass.DHReq req =
        DHServicesOuterClass.DHReq.newBuilder()
            .setHeader(header)
            .setPayload(payload)
            .setSignature(signature)
            .build();
    this.stub.dH(req);
  }

  public void shutdown() {
    this.channel.shutdown();
  }
}
