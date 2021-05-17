package pt.tecnico.ulisboa.hds.hdlt.server.session;

import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.sleep;

public class SessionsCleanupThread implements Runnable {

  private static final int sleepMillis = 5 * 60 * 1000;
  private final SessionsManager sessionsManager;
  private boolean stopped = false;

  public SessionsCleanupThread(SessionsManager sessionsManager) {
    this.sessionsManager = sessionsManager;
  }

  public void stop(Thread thread) {
    this.stopped = true;
    thread.interrupt();
  }

  @Override
  public void run() {
    while (!stopped) {
      this.sessionsManager.delInactiveSessions();
      sleep(sleepMillis);
    }
  }
}
