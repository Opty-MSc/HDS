package pt.tecnico.ulisboa.hds.hdlt.server.session;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SessionsManager {

  private final int expSec;
  private final ConcurrentMap<String, Session> sessionsAsServer = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Session> sessionsAsClient = new ConcurrentHashMap<>();

  public SessionsManager(int expSec) {
    this.expSec = expSec;
  }

  public Session newSessionAsServer(String name, SecretKey secKey, BigInteger nonce) {
    Session session = new Session(this.expSec, secKey, nonce);
    this.sessionsAsServer.put(name, session);
    return session;
  }

  public Session newSessionAsClient(String name, SecretKey secKey, BigInteger nonce) {
    Session session = new Session(this.expSec, secKey, nonce);
    this.sessionsAsClient.put(name, session);
    return session;
  }

  public Session getSessionAsServer(String name) {
    return this.getSession(name, this.sessionsAsServer);
  }

  public Session getSessionAsClient(String name) {
    return this.getSession(name, this.sessionsAsClient);
  }

  public void removeSessionAsClient(String name) {
    this.sessionsAsClient.remove(name);
  }

  public Session getSession(String name, ConcurrentMap<String, Session> sessions) {

    Session session = sessions.getOrDefault(name, null);

    if (session != null) {
      if (session.isValid()) {
        return session;
      }
      sessions.remove(name);
    }
    return null;
  }

  public void delInactiveSessions() {
    this.delInactiveSessions(this.sessionsAsServer);
    this.delInactiveSessions(this.sessionsAsClient);
  }

  private void delInactiveSessions(ConcurrentMap<String, Session> sessions) {
    for (Map.Entry<String, Session> e : sessions.entrySet()) {
      if (!e.getValue().isValid()) sessions.remove(e.getKey());
    }
  }
}
