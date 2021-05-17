package pt.tecnico.ulisboa.hds.hdlt.user.api;

import java.util.HashMap;
import java.util.Map;

public class SyncProofs {

  private final Map<String, byte[]> idProofs;
  private Integer nReplies;

  public SyncProofs() {
    this.nReplies = 0;
    this.idProofs = new HashMap<>();
  }

  public synchronized void addProof(String uname, byte[] proof) {
    this.idProofs.put(uname, proof);
    this.nReplies++;
    notifyAll();
  }

  public synchronized void newReply() {
    this.nReplies++;
    notifyAll();
  }

  public synchronized void resetNrOfReplies() {
    this.nReplies = 0;
  }

  public Integer getNrOfValidReplies() {
    return this.idProofs.size();
  }

  public Integer getNrOfReplies() {
    return this.nReplies;
  }

  public Map<String, byte[]> getIdProofs() {
    return this.idProofs;
  }
}
