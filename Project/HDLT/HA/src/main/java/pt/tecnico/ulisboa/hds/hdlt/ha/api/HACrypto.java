package pt.tecnico.ulisboa.hds.hdlt.ha.api;

import com.google.common.primitives.Bytes;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesOuterClass.Signature;
import pt.tecnico.ulisboa.hds.hdlt.ha.error.HARuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.ha.session.Session;
import pt.tecnico.ulisboa.hds.hdlt.ha.session.SessionsManager;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;

import javax.crypto.SecretKey;
import java.io.File;
import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class HACrypto {

  private static final Metadata.Key<Signature> signatureErrorKey =
      ProtoUtils.keyForProto(Signature.getDefaultInstance());

  private final SessionsManager sessionsManager;
  private final Set<String> unames;
  private final Set<String> sNames;
  private final PrivateKey uPrivKey;
  private final Map<String, PublicKey> pubKeys;
  private final int powDifficulty;

  public HACrypto(
      String uKSPath,
      String uCrtDirPath,
      String sCrtDirPath,
      String ksAlias,
      String ksPwd,
      int expSec,
      int powDifficulty) {
    this.sessionsManager = new SessionsManager(expSec);
    this.uPrivKey = Crypto.loadPrivKeyFromKS(new File(uKSPath), ksAlias, ksPwd);
    Map<String, PublicKey> sPubKeys = Crypto.parsePublicKeys(new File(sCrtDirPath));
    Map<String, PublicKey> uPubKeys = Crypto.parsePublicKeys(new File(uCrtDirPath));
    this.sNames = new HashSet<>(sPubKeys.keySet());
    this.unames = new HashSet<>(uPubKeys.keySet());
    this.pubKeys = sPubKeys;
    this.pubKeys.putAll(uPubKeys);
    this.powDifficulty = powDifficulty;
  }

  public boolean isServer(String sName) {
    return this.sNames.contains(sName);
  }

  public boolean isUser(String uname) {
    return this.unames.contains(uname);
  }

  public byte[] signPayload(byte[] payload) {
    return Crypto.cipherBytesRSAPriv(this.uPrivKey, Crypto.hash(Bytes.concat(payload)));
  }

  public byte[] unsignPayload(String name, byte[] signedPayload) {
    return Crypto.decipherBytesRSAPub(this.pubKeys.get(name), signedPayload);
  }

  public void checkAuthSignature(String name, byte[] signature, byte[] payload) {
    if (!Arrays.equals(this.unsignPayload(name, signature), Crypto.hash(payload))) {
      throw new HARuntimeException("Authenticity Tests Failed!");
    }
  }

  public void checkAuthHmac(String name, byte[] hmac, byte[] payload) {
    Session session = this.sessionsManager.getSession(name);
    if (session == null || !Arrays.equals(Crypto.hmac(session.getSecKey(), payload), hmac)) {
      throw new HARuntimeException("Authenticity Tests Failed!");
    }
  }

  public void checkErrorAuth(String name, StatusRuntimeException e, BigInteger nonce) {
    Metadata metadata = e.getTrailers();
    if (metadata == null || !metadata.containsKey(signatureErrorKey)) {
      throw new HARuntimeException("Authenticity Checks Failed!");
    }
    Signature signature = metadata.get(signatureErrorKey);
    if (signature == null) {
      throw new HARuntimeException("Authenticity Checks Failed!");
    }
    String payload =
        String.format(
            "%d%s%d", e.getStatus().getCode().value(), e.getStatus().getDescription(), nonce);
    this.checkAuthSignature(name, signature.getByteString().toByteArray(), payload.getBytes());
  }

  public long generateProofOfWork(byte[] bytes) {
    return Crypto.generateProofOfWork(bytes, this.powDifficulty);
  }

  public Session newSession(String name, SecretKey secKey, BigInteger nonce) {
    return this.sessionsManager.newSession(name, secKey, nonce);
  }

  public Session getSession(String name) {
    return this.sessionsManager.getSession(name);
  }
}
