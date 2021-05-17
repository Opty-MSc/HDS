package pt.tecnico.ulisboa.hds.hdlt.user.api;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.StreamObserver;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Proof;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesOuterClass.Signature;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesGrpc.UserUserServicesImplBase;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesOuterClass.RequestULProofRep;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesOuterClass.RequestULProofReq;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.lib.error.AssertError;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.math.BigInteger;

import static io.grpc.Status.FAILED_PRECONDITION;
import static io.grpc.Status.INVALID_ARGUMENT;

public class UserUserServicesImpl extends UserUserServicesImplBase {

  private final String uname;
  private final GridManager grid;
  private final UserCrypto uCrypto;
  private final Integer uMaxDistance;

  public UserUserServicesImpl(
      String uname, GridManager grid, UserCrypto uCrypto, Integer uMaxDistance) {
    this.uname = uname;
    this.grid = grid;
    this.uCrypto = uCrypto;
    this.uMaxDistance = uMaxDistance;
  }

  public static void onError(
      UserCrypto uCrypto,
      StreamObserver<RequestULProofRep> resObs,
      Status status,
      String errorMsg,
      BigInteger nonce) {
    Metadata metadata = new Metadata();
    String payload = String.format("%d%s%d", status.getCode().value(), errorMsg, nonce);
    metadata.put(
        ProtoUtils.keyForProto(Signature.getDefaultInstance()),
        Signature.newBuilder()
            .setByteString(ByteString.copyFrom(uCrypto.signPayload(payload.getBytes())))
            .build());
    resObs.onError(status.withDescription(errorMsg).asRuntimeException(metadata));
  }

  @Override
  public void requestULProof(RequestULProofReq req, StreamObserver<RequestULProofRep> resObs) {

    Integer epoch = req.getHeader().getEpoch();
    String reqUname = req.getHeader().getUname();
    BigInteger nonce = new BigInteger(1, req.getHeader().getNonce().toByteArray());
    Location location = this.grid.getLocation(this.uname, epoch);
    Location reqLocation = this.grid.getLocation(reqUname, epoch);

    try {
      this.uCrypto.checkAuthSignature(
          reqUname, req.getSignature().toByteArray(), req.getHeader().toByteArray());
    } catch (AssertError | UserRuntimeException e) {
      onError(this.uCrypto, resObs, INVALID_ARGUMENT, "Authenticity Tests Failed!", nonce);
      return;
    }

    if (!location.isNearBy(reqLocation, this.uMaxDistance)) {
      onError(this.uCrypto, resObs, FAILED_PRECONDITION, "User Too Far Away!", nonce);
      return;
    }

    Proof proof =
        Proof.newBuilder()
            .setUname(reqUname)
            .setEpoch(epoch)
            .setX(reqLocation.getX())
            .setY(reqLocation.getY())
            .build();

    byte[] signedProof = this.uCrypto.signPayload(proof.toByteArray());
    resObs.onNext(
        RequestULProofRep.newBuilder()
            .setSignedProof(ByteString.copyFrom(signedProof))
            .setSignature(
                ByteString.copyFrom(
                    this.uCrypto.signPayload(
                        Bytes.concat(proof.toByteArray(), nonce.toByteArray()))))
            .build());
    resObs.onCompleted();
  }
}
