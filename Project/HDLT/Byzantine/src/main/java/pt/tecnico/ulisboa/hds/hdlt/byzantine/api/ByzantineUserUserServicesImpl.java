package pt.tecnico.ulisboa.hds.hdlt.byzantine.api;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Proof;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesGrpc.UserUserServicesImplBase;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesOuterClass.RequestULProofRep;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesOuterClass.RequestULProofReq;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserCrypto;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserUserServicesImpl;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.math.BigInteger;

public class ByzantineUserUserServicesImpl extends UserUserServicesImplBase {

  public static final int MALFORMED = 0;
  public static final int ALWAYS_SIGN = 1;
  public static final int MALFORMED_EXCEPTION = 2;
  public static final int DOES_NOT_ANSWER = 3;
  public static final int NEVER_SIGN = 4;
  private final GridManager grid;
  private final UserCrypto uCrypto;
  private int mode = NEVER_SIGN;

  public ByzantineUserUserServicesImpl(GridManager grid, UserCrypto uCrypto) {
    this.grid = grid;
    this.uCrypto = uCrypto;
  }

  public void setMode(int mode) {
    this.mode = mode;
  }

  @Override
  public void requestULProof(RequestULProofReq req, StreamObserver<RequestULProofRep> resObs) {

    String reqUname = req.getHeader().getUname();
    Integer epoch = req.getHeader().getEpoch();
    BigInteger nonce = new BigInteger(1, req.getHeader().getNonce().toByteArray());
    Location reqLocation = this.grid.getLocation(reqUname, epoch);

    switch (this.mode) {
      case MALFORMED:
        System.out.println("Purposefully Sending Malformed Proof!");
        this.doSignature(reqUname, epoch, nonce, new Location(-1, -1), resObs);
        break;
      case ALWAYS_SIGN:
        System.out.println("Purposefully Signing Proof Request!");
        this.doSignature(reqUname, epoch, nonce, reqLocation, resObs);
        break;
      case MALFORMED_EXCEPTION:
        System.out.println("Purposefully Denying Proof Request (Malformed)!");
        // Never Sign
        UserUserServicesImpl.onError(
            this.uCrypto,
            resObs,
            Status.FAILED_PRECONDITION,
            "User Too Far Away!",
            Crypto.generateRandomNonce());
        return;
      case DOES_NOT_ANSWER:
        return;
      default:
        System.out.println("Purposefully Denying Proof Request!");
        // Never Sign
        UserUserServicesImpl.onError(
            this.uCrypto, resObs, Status.FAILED_PRECONDITION, "User Too Far Away!", nonce);
        return;
    }
    resObs.onCompleted();
  }

  private void doSignature(
      String reqUname,
      Integer epoch,
      BigInteger nonce,
      Location reqLocation,
      StreamObserver<RequestULProofRep> resObs) {

    Proof proof =
        Proof.newBuilder()
            .setUname(reqUname)
            .setEpoch(epoch)
            .setX(reqLocation.getX())
            .setY(reqLocation.getY())
            .build();

    resObs.onNext(
        RequestULProofRep.newBuilder()
            .setSignedProof(ByteString.copyFrom(this.uCrypto.signPayload(proof.toByteArray())))
            .setSignature(
                ByteString.copyFrom(
                    this.uCrypto.signPayload(
                        Bytes.concat(proof.toByteArray(), nonce.toByteArray()))))
            .build());
  }
}
