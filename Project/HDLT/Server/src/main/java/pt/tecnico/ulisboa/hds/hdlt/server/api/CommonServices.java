package pt.tecnico.ulisboa.hds.hdlt.server.api;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Report;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.lib.error.AssertError;
import pt.tecnico.ulisboa.hds.hdlt.server.error.ServerStatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.server.session.Session;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.grpc.Status.INVALID_ARGUMENT;
import static io.grpc.Status.UNAUTHENTICATED;

public class CommonServices {

  public static void verifySession(ServerCrypto sCrypto, Session session, BigInteger nonce) {
    if (session == null) {
      throw new ServerStatusRuntimeException(
          sCrypto, UNAUTHENTICATED, "Session not established!", nonce);
    }
    if (!session.isNonceValid(nonce)) {
      throw new ServerStatusRuntimeException(
          sCrypto, UNAUTHENTICATED, "Freshness Tests Failed!", nonce);
    }
  }

  public static void verifyReport(
      ServerCrypto sCrypto,
      int nByzantineUsers,
      int byzantineQuorum,
      BigInteger nonce,
      Report report,
      boolean isComplete) {

    Map<String, ByteString> uIdProofs = report.getUReport().getUIdProofsMap();

    int nUIdProofs = uIdProofs.size();
    if (nUIdProofs < nByzantineUsers) {
      throw new ServerStatusRuntimeException(
          sCrypto, INVALID_ARGUMENT, "Number of User Proofs Insufficient!", nonce);
    }

    byte[] uProofHash = Crypto.hash(report.getUReport().getProof().toByteArray());

    Set<String> seenUSigners = new HashSet<>();
    seenUSigners.add(report.getUReport().getProof().getUname());
    int nUInvalidIdProofs = 0;

    for (Map.Entry<String, ByteString> uIdProof :
        report.getUReport().getUIdProofsMap().entrySet()) {
      String uSigner = uIdProof.getKey();
      byte[] uSignedProof = uIdProof.getValue().toByteArray();

      try {
        if (!sCrypto.isUser(uSigner)) nUInvalidIdProofs++;
        else {
          byte[] uProofUnsigned = sCrypto.unsignPayload(uSigner, uSignedProof);
          if (!Arrays.equals(uProofUnsigned, uProofHash) || !seenUSigners.add(uSigner)) {
            nUInvalidIdProofs++;
          } else {
            // Valid User Signature
            if (isComplete) verifyServerIdProofs(sCrypto, byzantineQuorum, nonce, report, uSigner);
          }
        }
      } catch (AssertError e) {
        nUInvalidIdProofs++;
      }
      if (nUIdProofs - nUInvalidIdProofs < nByzantineUsers) {
        throw new ServerStatusRuntimeException(
            sCrypto, INVALID_ARGUMENT, "Number of User Proofs Insufficient!", nonce);
      }
    }
  }

  private static void verifyServerIdProofs(
      ServerCrypto sCrypto, int byzantineQuorum, BigInteger nonce, Report report, String uSigner) {

    if (!report.getSIdProofsMap().containsKey(uSigner)) {
      throw new ServerStatusRuntimeException(
          sCrypto, INVALID_ARGUMENT, "Number of Server Proofs Insufficient!", nonce);
    }

    Map<String, ByteString> sIdProofs =
        report.getSIdProofsMap().get(uSigner).getSIdProofsValuesMap();

    int nSIdProofs = sIdProofs.size();
    if (nSIdProofs < byzantineQuorum) {
      throw new ServerStatusRuntimeException(
          sCrypto, INVALID_ARGUMENT, "Number of Server Proofs Insufficient!", nonce);
    }

    byte[] sProofHash =
        Crypto.hash(Bytes.concat(report.getUReport().getProof().toByteArray(), uSigner.getBytes()));

    Set<String> seenSSigners = new HashSet<>();
    int nSInvalidIdProofs = 0;

    for (Map.Entry<String, ByteString> sIdProof : sIdProofs.entrySet()) {
      String sSigner = sIdProof.getKey();
      byte[] sSignedProof = sIdProof.getValue().toByteArray();

      try {
        if (!sCrypto.isServer(sSigner)) nSInvalidIdProofs++;
        else {
          byte[] sProofUnsigned = sCrypto.unsignPayload(sSigner, sSignedProof);
          if (!Arrays.equals(sProofUnsigned, sProofHash) || !seenSSigners.add(sSigner)) {
            nSInvalidIdProofs++;
          }
        }
      } catch (AssertError e) {
        nSInvalidIdProofs++;
      }
      if (nSIdProofs - nSInvalidIdProofs < byzantineQuorum) {
        throw new ServerStatusRuntimeException(
            sCrypto, INVALID_ARGUMENT, "Number of Server Proofs Insufficient!", nonce);
      }
    }
  }

  public static Map<String, ByteString> toByteString(Map<String, byte[]> map) {
    return map.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> ByteString.copyFrom(e.getValue())));
  }

  public static Map<String, byte[]> toByteArray(Map<String, ByteString> map) {
    return map.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toByteArray()));
  }
}
