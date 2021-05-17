package pt.tecnico.ulisboa.hds.hdlt.user.api;

import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.FutureCallback;
import io.grpc.StatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesOuterClass.RequestULProofRep;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;

import javax.annotation.Nonnull;
import java.math.BigInteger;

import static io.grpc.Status.Code.UNAVAILABLE;

public class RequestULCallback implements FutureCallback<RequestULProofRep> {

  private final String uname;
  private final UserCrypto uCrypto;
  private final SyncProofs syncProofs;
  private final byte[] proof;
  private final BigInteger nonce;

  public RequestULCallback(
      String uname, UserCrypto uCrypto, SyncProofs syncProofs, byte[] proof, BigInteger nonce) {
    super();
    this.uname = uname;
    this.uCrypto = uCrypto;
    this.syncProofs = syncProofs;
    this.proof = proof;
    this.nonce = nonce;
  }

  @Override
  public void onSuccess(RequestULProofRep rep) {
    try {
      this.uCrypto.checkAuthSignature(
          this.uname,
          rep.getSignature().toByteArray(),
          Bytes.concat(this.proof, this.nonce.toByteArray()));
      this.uCrypto.checkAuthSignature(this.uname, rep.getSignedProof().toByteArray(), this.proof);
      this.syncProofs.addProof(this.uname, rep.getSignedProof().toByteArray());
    } catch (UserRuntimeException e) {
      this.syncProofs.newReply();
      System.out.printf("Invalid Response From User '%s'!%n", this.uname);
    }
  }

  @Override
  public void onFailure(@Nonnull Throwable throwable) {
    this.syncProofs.newReply();
    try {
      this.uCrypto.checkErrorAuth(this.uname, (StatusRuntimeException) throwable, nonce);
    } catch (UserRuntimeException e) {
      if (((StatusRuntimeException) throwable).getStatus().getCode() != UNAVAILABLE) {
        System.out.printf("Invalid Exception From User '%s'!%n", this.uname);
      }
    }
  }
}
