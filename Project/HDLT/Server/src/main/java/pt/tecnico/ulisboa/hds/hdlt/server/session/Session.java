package pt.tecnico.ulisboa.hds.hdlt.server.session;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.time.LocalDateTime;

public class Session {

  public final int expSec;
  private final SecretKey secKey;
  private LocalDateTime exp;
  private BigInteger nonce;

  public Session(int expSec, SecretKey secKey, BigInteger nonce) {
    this.expSec = expSec;
    this.secKey = secKey;
    this.nonce = nonce;
    this.exp = LocalDateTime.now().plusSeconds(expSec);
  }

  public boolean isNonceValid(BigInteger nonce) {
    if (nonce.equals(this.nonce.add(BigInteger.ONE))) {
      this.nonce = nonce;
      return true;
    }
    return false;
  }

  public BigInteger newNonce() {
    this.nonce = this.nonce.add(BigInteger.ONE);
    return this.nonce;
  }

  public synchronized void invalidate() {
    this.exp = null;
  }

  public synchronized boolean isValid() {
    return this.exp != null && LocalDateTime.now().isBefore(this.exp);
  }

  public SecretKey getSecKey() {
    return this.secKey;
  }

  public BigInteger getNonce() {
    return this.nonce;
  }

  public void setNonce(BigInteger nonce) {
    this.nonce = nonce;
  }
}
