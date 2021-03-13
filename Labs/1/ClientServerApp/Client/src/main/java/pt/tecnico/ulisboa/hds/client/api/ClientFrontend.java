package pt.tecnico.ulisboa.hds.client.api;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import pt.tecnico.ulisboa.hds.contract.ClientServer.EchoBody;
import pt.tecnico.ulisboa.hds.contract.ClientServer.EchoReply;
import pt.tecnico.ulisboa.hds.contract.ClientServer.EchoRequest;
import pt.tecnico.ulisboa.hds.contract.ClientServer.SecureMessage;
import pt.tecnico.ulisboa.hds.contract.ServerServicesGrpc;
import pt.tecnico.ulisboa.hds.contract.ServerServicesGrpc.ServerServicesBlockingStub;
import pt.tecnico.ulisboa.hds.lib.crypto.Crypto;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

public class ClientFrontend {

  private final ManagedChannel channel;
  private final ServerServicesBlockingStub stub;
  private final PublicKey sPubKey;
  private final PrivateKey cPrivKey;
  private final SecretKey aesKey;

  public ClientFrontend(
      String sHost, int sPort, PublicKey sPubKey, PrivateKey cPrivKey, SecretKey aesKey) {
    this.sPubKey = sPubKey;
    this.cPrivKey = cPrivKey;
    this.aesKey = aesKey;
    this.channel = ManagedChannelBuilder.forAddress(sHost, sPort).usePlaintext().build();
    this.stub = ServerServicesGrpc.newBlockingStub(this.channel);
  }

  public String echo(String msg) throws ClientFrontendError {
    IvParameterSpec iv = new IvParameterSpec(Crypto.generateRandomIV());
    EchoBody body = EchoBody.newBuilder().setMessage(msg).build();
    byte[] bodyBytes = body.toByteArray();
    byte[] signature = Crypto.cipherBytesRSAPriv(this.cPrivKey, Crypto.hash(bodyBytes));
    byte[] encryptedBody = Crypto.cipherBytesAES(this.aesKey, iv, bodyBytes);
    EchoReply reply;

    try {
      reply =
          stub.echo(
              EchoRequest.newBuilder()
                  .setEncryptedBody(ByteString.copyFrom(encryptedBody))
                  .setSecure(
                      SecureMessage.newBuilder()
                          .setIv(ByteString.copyFrom(iv.getIV()))
                          .setSignature(ByteString.copyFrom(signature))
                          .build())
                  .build());
    } catch (StatusRuntimeException e) {
      throw new ClientFrontendError(e.getMessage(), e);
    }

    iv = new IvParameterSpec(reply.getSecure().getIv().toByteArray());
    signature = reply.getSecure().getSignature().toByteArray();
    encryptedBody = reply.getEncryptedBody().toByteArray();
    bodyBytes = Crypto.decipherBytesAES(this.aesKey, iv, encryptedBody);

    if (!Arrays.equals(
        Crypto.decipherBytesRSAPub(this.sPubKey, signature), Crypto.hash(bodyBytes))) {
      throw new ClientFrontendError("Invalid Signature!");
    }

    try {
      body = EchoBody.parseFrom(bodyBytes);
    } catch (InvalidProtocolBufferException e) {
      throw new ClientFrontendError("Invalid Body!", e);
    }
    return body.getMessage();
  }

  public void shutdown() {
    channel.shutdown();
  }
}
