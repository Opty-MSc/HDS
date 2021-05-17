package pt.tecnico.ulisboa.hds.hdlt.lib.crypto;

import pt.tecnico.ulisboa.hds.hdlt.lib.error.AssertError;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

public class Crypto {

  private static final int IV_SIZE = 16;
  private static final int DH_KEY_SIZE = 3072;
  private static final SecureRandom random = new SecureRandom();

  /*================
  | Diffie-Hellman |
  ================*/

  public static KeyPair doDHFirstPhase(DHParameterSpec dhParamSpec) {
    // Generates a KeyPair to Diffie-Hellman
    try {
      KeyPairGenerator kPairGen = KeyPairGenerator.getInstance("DH");
      if (dhParamSpec == null) kPairGen.initialize(DH_KEY_SIZE);
      else kPairGen.initialize(dhParamSpec);
      return kPairGen.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new AssertError(Crypto.class.getSimpleName(), "doDHFirstPhase", e);
    }
  }

  public static SecretKey doDHSecondPhase(
      DHParameterSpec dhParamSpec, KeyPair kPair, BigInteger y) {
    // Generates an AES Key to Diffie-Hellman
    try {
      KeyAgreement kAgree = KeyAgreement.getInstance("DH");
      kAgree.init(kPair.getPrivate());
      KeyFactory kFactory = KeyFactory.getInstance("DH");
      DHPublicKeySpec pubKeySpec = new DHPublicKeySpec(y, dhParamSpec.getP(), dhParamSpec.getG());
      PublicKey pubKey = kFactory.generatePublic(pubKeySpec);
      kAgree.doPhase(pubKey, true);
      return new SecretKeySpec(kAgree.generateSecret(), 0, 16, "AES");
    } catch (GeneralSecurityException e) {
      throw new AssertError(Crypto.class.getSimpleName(), "doDHSecondPhase", e);
    }
  }

  /*==============
  | Load RSA Key |
  ==============*/

  public static PublicKey loadPubKeyFromCrt(File file) {
    try (FileInputStream fis = new FileInputStream(file)) {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      X509Certificate crt = (X509Certificate) cf.generateCertificate(fis);
      return crt.getPublicKey();
    } catch (GeneralSecurityException | IOException e) {
      throw new AssertError(Crypto.class.getTypeName(), "loadPubKeyFromCrt", e);
    }
  }

  public static PrivateKey loadPrivKeyFromKS(File file, String ksAlias, String ksPwd) {
    try (FileInputStream fis = new FileInputStream(file)) {
      KeyStore ks = KeyStore.getInstance("JKS");
      ks.load(fis, ksPwd.toCharArray());
      PasswordProtection pp = new KeyStore.PasswordProtection(ksPwd.toCharArray());
      PrivateKeyEntry pke = (PrivateKeyEntry) ks.getEntry(ksAlias, pp);
      return pke.getPrivateKey();
    } catch (GeneralSecurityException | IOException e) {
      throw new AssertError(Crypto.class.getTypeName(), "loadPrivKeyFromKS", e);
    }
  }

  /*=======================
  | RSA Cipher / Decipher |
  =======================*/

  public static byte[] cipherBytesRSAPriv(PrivateKey privKey, byte[] bytes) {
    return cryptoRSA(Cipher.ENCRYPT_MODE, privKey, bytes);
  }

  public static byte[] decipherBytesRSAPub(PublicKey pubKey, byte[] bytes) {
    return cryptoRSA(Cipher.DECRYPT_MODE, pubKey, bytes);
  }

  private static byte[] cryptoRSA(int mode, Key key, byte[] bytes) {
    try {
      Cipher cipher = Cipher.getInstance("RSA");
      cipher.init(mode, key);
      return cipher.doFinal(bytes);
    } catch (GeneralSecurityException e) {
      throw new AssertError(Crypto.class.getTypeName(), "cryptoRSA", e);
    }
  }

  /*=======================
  | AES Cipher / Decipher |
  =======================*/

  public static byte[] cipherBytesAES(SecretKey aesKey, IvParameterSpec iv, byte[] bytes) {
    return cryptoAES(Cipher.ENCRYPT_MODE, aesKey, iv, bytes);
  }

  public static byte[] decipherBytesAES(SecretKey aesKey, IvParameterSpec iv, byte[] bytes) {
    return cryptoAES(Cipher.DECRYPT_MODE, aesKey, iv, bytes);
  }

  public static byte[] cryptoAES(int mode, SecretKey aesKey, IvParameterSpec iv, byte[] bytes) {
    try {
      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(mode, aesKey, iv);
      return cipher.doFinal(bytes);
    } catch (GeneralSecurityException e) {
      throw new AssertError(Crypto.class.getTypeName(), "cryptoAES", e);
    }
  }

  /*========
  | Hashes |
  ========*/

  public static byte[] hmac(SecretKey secKey, byte[] bytes) {
    try {
      Mac mac = Mac.getInstance("HmacSHA512");
      mac.init(secKey);
      return mac.doFinal(bytes);
    } catch (GeneralSecurityException e) {
      throw new AssertError(Crypto.class.getTypeName(), "hmac", e);
    }
  }

  public static byte[] hash(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-512");
      return md.digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertError(Crypto.class.getTypeName(), "hash", e);
    }
  }

  /*===============
  | Proof Of Work |
  ===============*/

  public static long generateProofOfWork(byte[] bytes, int powDifficulty) {
    long pow = 0L;
    while (!verifyProofOfWork(bytes, pow, powDifficulty)) pow++;
    return pow;
  }

  public static boolean verifyProofOfWork(byte[] bytes, long pow, int powDifficulty) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash =
          md.digest(ByteBuffer.allocate(bytes.length + Long.BYTES).put(bytes).putLong(pow).array());
      for (int i = 0; i < powDifficulty; i++) {
        if (hash[i] != 0) return false;
      }
      return true;
    } catch (NoSuchAlgorithmException e) {
      throw new AssertError(Crypto.class.getTypeName(), "verifyProofOfWork", e);
    }
  }

  /*===================
  | Random Generators |
  ===================*/

  public static byte[] generateRandomIV() {
    byte[] iv = new byte[IV_SIZE];
    random.nextBytes(iv);
    return iv;
  }

  public static BigInteger generateRandomNonce() {
    return new BigInteger(16 * 8, random);
  }

  /*===================
  | Parse Public Keys |
  ===================*/

  public static Map<String, PublicKey> parsePublicKeys(File crtDir) {
    if (!crtDir.isDirectory()) {
      throw new AssertError(
          Crypto.class.getSimpleName(),
          String.format("[%s] is not a Directory!", crtDir.getName()));
    }
    File[] crtFiles = crtDir.listFiles();
    if (crtFiles == null) {
      throw new AssertError(
          Crypto.class.getSimpleName(),
          String.format("[%s] Directory is Empty!", crtDir.getName()));
    }
    Map<String, PublicKey> pubKeys = new HashMap<>();
    for (File crtFile : crtFiles) {
      String crtFilename = crtFile.getName();
      pubKeys.put(
          crtFilename.substring(0, crtFilename.lastIndexOf('.')),
          Crypto.loadPubKeyFromCrt(crtFile));
    }
    return pubKeys;
  }
}
