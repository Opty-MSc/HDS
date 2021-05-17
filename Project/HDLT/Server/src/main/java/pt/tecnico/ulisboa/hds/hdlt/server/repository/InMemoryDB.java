package pt.tecnico.ulisboa.hds.hdlt.server.repository;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

public class InMemoryDB {

  private static final Set<BigInteger> nonces = new HashSet<>();

  public static boolean addNonce(BigInteger nonce) {
    synchronized (nonces) {
      return nonces.add(nonce);
    }
  }

  public static void addAllNonces(Set<BigInteger> nonces) {
    InMemoryDB.nonces.addAll(nonces);
  }
}
