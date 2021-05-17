package pt.tecnico.ulisboa.hds.hdlt.server.api;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import pt.tecnico.ulisboa.hds.hdlt.contract.dh.DHServicesOuterClass.Signature;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.server.error.ServerStatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.server.session.Session;
import pt.tecnico.ulisboa.hds.hdlt.server.session.SessionsManager;

import javax.crypto.SecretKey;
import java.io.File;
import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static io.grpc.Status.INVALID_ARGUMENT;

public class ServerCrypto {

  private static final Metadata.Key<Signature> signatureErrorKey =
      ProtoUtils.keyForProto(Signature.getDefaultInstance());

  private final SessionsManager sessionsManager;
  private final PrivateKey sPrivKey;
  private final Set<String> unames;
  private final Set<String> sNames;
  private final Map<String, PublicKey> pubKeys;
  private final int powDifficulty;

  public ServerCrypto(
      String sKSPath,
      String sCrtDirPath,
      String uCrtDirPath,
      String haCrtPath,
      String ksAlias,
      String ksPwd,
      SessionsManager sessionsManager,
      int powDifficulty) {
    this.sessionsManager = sessionsManager;
    this.sPrivKey = Crypto.loadPrivKeyFromKS(new File(sKSPath), ksAlias, ksPwd);
    Map<String, PublicKey> sPubKeys = Crypto.parsePublicKeys(new File(sCrtDirPath));
    Map<String, PublicKey> uPubKeys = Crypto.parsePublicKeys(new File(uCrtDirPath));
    this.sNames = new HashSet<>(sPubKeys.keySet());
    this.unames = new HashSet<>(uPubKeys.keySet());
    uPubKeys.put("HA", Crypto.loadPubKeyFromCrt(new File(haCrtPath)));
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
    return Crypto.cipherBytesRSAPriv(this.sPrivKey, Crypto.hash(payload));
  }

  public byte[] unsignPayload(String name, byte[] signedPayload) {
    return Crypto.decipherBytesRSAPub(this.pubKeys.get(name), signedPayload);
  }

  public byte[] generateHmac(Session session, byte[] payload) {
    return Crypto.hmac(session.getSecKey(), payload);
  }

  public void checkAuthSignature(String name, byte[] signature, byte[] payload, BigInteger nonce) {
    if (!Arrays.equals(this.unsignPayload(name, signature), Crypto.hash(payload))) {
      throw new ServerStatusRuntimeException(
          this, INVALID_ARGUMENT, "Authenticity Tests Failed!", nonce);
    }
  }

  public void checkAuthHmac(Session session, byte[] hmac, byte[] payload, BigInteger nonce) {
    if (!Arrays.equals(this.generateHmac(session, payload), hmac)) {
      throw new ServerStatusRuntimeException(
          this, INVALID_ARGUMENT, "Authenticity Tests Failed!", nonce);
    }
  }

  public void checkErrorAuth(String name, StatusRuntimeException e, BigInteger nonce) {
    Metadata metadata = e.getTrailers();
    if (metadata == null || !metadata.containsKey(signatureErrorKey)) {
      throw new ServerStatusRuntimeException(
          this, INVALID_ARGUMENT, "Authenticity Checks Failed!", nonce);
    }
    Signature signature = metadata.get(signatureErrorKey);
    if (signature == null) {
      throw new ServerStatusRuntimeException(
          this, INVALID_ARGUMENT, "Authenticity Checks Failed!", nonce);
    }
    String payload =
        String.format(
            "%d%s%d", e.getStatus().getCode().value(), e.getStatus().getDescription(), nonce);
    this.checkAuthSignature(
        name, signature.getByteString().toByteArray(), payload.getBytes(), nonce);
  }

  public boolean verifyProofOfWork(byte[] bytes, long pow) {
    return Crypto.verifyProofOfWork(bytes, pow, this.powDifficulty);
  }

  public Session newSessionAsServer(String name, SecretKey secKey, BigInteger nonce) {
    return this.sessionsManager.newSessionAsServer(name, secKey, nonce);
  }

  public Session newSessionAsClient(String name, SecretKey secKey, BigInteger nonce) {
    return this.sessionsManager.newSessionAsClient(name, secKey, nonce);
  }

  public Session getSessionAsServer(String name) {
    return this.sessionsManager.getSessionAsServer(name);
  }

  public Session getSessionAsClient(String name) {
    return this.sessionsManager.getSessionAsClient(name);
  }

  public void removeSessionAsClient(String name) {
    this.sessionsManager.removeSessionAsClient(name);
  }
}
