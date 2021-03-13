package pt.tecnico.ulisboa.hds.server.api;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import pt.tecnico.ulisboa.hds.contract.ClientServer;
import pt.tecnico.ulisboa.hds.contract.ClientServer.EchoBody;
import pt.tecnico.ulisboa.hds.contract.ClientServer.EchoReply;
import pt.tecnico.ulisboa.hds.contract.ClientServer.EchoRequest;
import pt.tecnico.ulisboa.hds.contract.ServerServicesGrpc.ServerServicesImplBase;
import pt.tecnico.ulisboa.hds.lib.crypto.Crypto;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

public class ServerServicesImpl extends ServerServicesImplBase {

  private final PublicKey cPubKey;
  private final PrivateKey sPrivKey;
  private final SecretKey aesKey;

  public ServerServicesImpl(PublicKey cPubKey, PrivateKey sPrivKey, SecretKey aesKey) {
    super();
    this.cPubKey = cPubKey;
    this.sPrivKey = sPrivKey;
    this.aesKey = aesKey;
  }

  @Override
  public void echo(EchoRequest req, StreamObserver<EchoReply> resObs) {
    IvParameterSpec iv = new IvParameterSpec(req.getSecure().getIv().toByteArray());
    byte[] signature = req.getSecure().getSignature().toByteArray();
    byte[] encryptedBody = req.getEncryptedBody().toByteArray();
    byte[] bodyBytes = Crypto.decipherBytesAES(this.aesKey, iv, encryptedBody);
    EchoBody body;

    if (!Arrays.equals(
        Crypto.decipherBytesRSAPub(this.cPubKey, signature), Crypto.hash(bodyBytes))) {
      resObs.onError(
          Status.INVALID_ARGUMENT.withDescription("Invalid Signature!").asRuntimeException());
      return;
    }

    try {
      body = EchoBody.parseFrom(bodyBytes);
    } catch (InvalidProtocolBufferException e) {
      resObs.onError(Status.INVALID_ARGUMENT.withDescription("Invalid Body!").asRuntimeException());
      return;
    }

    iv = new IvParameterSpec(Crypto.generateRandomIV());
    body = EchoBody.newBuilder().setMessage(body.getMessage()).build();
    bodyBytes = body.toByteArray();
    signature = Crypto.cipherBytesRSAPriv(this.sPrivKey, Crypto.hash(bodyBytes));
    encryptedBody = Crypto.cipherBytesAES(this.aesKey, iv, bodyBytes);

    resObs.onNext(
        EchoReply.newBuilder()
            .setEncryptedBody(ByteString.copyFrom(encryptedBody))
            .setSecure(
                ClientServer.SecureMessage.newBuilder()
                    .setIv(ByteString.copyFrom(iv.getIV()))
                    .setSignature(ByteString.copyFrom(signature))
                    .build())
            .build());
    resObs.onCompleted();
  }
}
