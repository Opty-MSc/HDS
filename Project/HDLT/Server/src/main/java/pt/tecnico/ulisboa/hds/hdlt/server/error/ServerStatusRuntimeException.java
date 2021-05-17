package pt.tecnico.ulisboa.hds.hdlt.server.error;

import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesOuterClass.Signature;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ServerCrypto;

import java.math.BigInteger;

public class ServerStatusRuntimeException extends StatusRuntimeException {

  public ServerStatusRuntimeException(Status status, String errorMsg) {
    super(status.withDescription(errorMsg));
  }

  public ServerStatusRuntimeException(
      ServerCrypto sCrypto, Status status, String errorMsg, BigInteger nonce) {
    super(
        status.withDescription(errorMsg), generateSignedMetadata(sCrypto, status, errorMsg, nonce));
  }

  private static Metadata generateSignedMetadata(
      ServerCrypto serverCrypto, Status status, String errorMsg, BigInteger nonce) {
    Metadata metadata = new Metadata();
    String payload = String.format("%d%s%d", status.getCode().value(), errorMsg, nonce);

    metadata.put(
        ProtoUtils.keyForProto(Signature.getDefaultInstance()),
        Signature.newBuilder()
            .setByteString(ByteString.copyFrom(serverCrypto.signPayload(payload.getBytes())))
            .build());
    return metadata;
  }
}
