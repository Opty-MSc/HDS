package pt.tecnico.ulisboa.hds.hdlt.ha.session;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SessionsManager {

  private final int expSec;
  private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

  public SessionsManager(int expSec) {
    this.expSec = expSec;
  }

  public Session newSession(String sName, SecretKey secKey, BigInteger nonce) {
    Session session = new Session(this.expSec, secKey, nonce);
    this.sessions.put(sName, session);
    return session;
  }

  public Session getSession(String sName) {

    Session session = this.sessions.getOrDefault(sName, null);

    if (session != null) {
      if (session.isValid()) {
        return session;
      }
      this.sessions.remove(sName);
    }
    return null;
  }
}
