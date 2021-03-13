package pt.tecnico.ulisboa.hds.lib.crypto;

import pt.tecnico.ulisboa.hds.lib.error.AssertError;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public class Crypto {

  private static final int IV_SIZE = 16;
  private static final SecureRandom random = new SecureRandom();

  /*==============
  | Load RSA Key |
  ==============*/

  public static PublicKey loadPubKey(File file) {
    try (FileInputStream fis = new FileInputStream(file)) {
      X509EncodedKeySpec spec = new X509EncodedKeySpec(fis.readAllBytes());
      KeyFactory kf = KeyFactory.getInstance("RSA");
      return kf.generatePublic(spec);
    } catch (GeneralSecurityException | IOException e) {
      throw new AssertError(Crypto.class.getTypeName(), "loadPubKey", e);
    }
  }

  public static PrivateKey loadPrivKey(File file) {
    try (FileInputStream fis = new FileInputStream(file)) {
      PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(fis.readAllBytes());
      KeyFactory kf = KeyFactory.getInstance("RSA");
      return kf.generatePrivate(spec);
    } catch (GeneralSecurityException | IOException e) {
      throw new AssertError(Crypto.class.getTypeName(), "loadPrivKey", e);
    }
  }

  /*==============
  | Load AES Key |
  ==============*/

  public static SecretKey loadAESKey(File file) {
    try (FileInputStream fis = new FileInputStream(file)) {
      return new SecretKeySpec(fis.readAllBytes(), 0, 16, "AES");
    } catch (IOException e) {
      throw new AssertError(Crypto.class.getTypeName(), "loadAESKey", e);
    }
  }

  /*=======================
  | RSA Cipher / Decipher |
  =======================*/

  public static byte[] cipherBytesRSAPriv(PrivateKey privKey, byte[] bytes) {
    return cryptoRSA(Cipher.ENCRYPT_MODE, privKey, bytes);
  }

  public static byte[] decipherBytesRSAPriv(PrivateKey privKey, byte[] bytes) {
    return cryptoRSA(Cipher.DECRYPT_MODE, privKey, bytes);
  }

  public static byte[] cipherBytesRSAPub(PublicKey pubKey, byte[] bytes) {
    return cryptoRSA(Cipher.ENCRYPT_MODE, pubKey, bytes);
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

  public static byte[] hash(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-512");
      return md.digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertError(Crypto.class.getTypeName(), "hash", e);
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
}
