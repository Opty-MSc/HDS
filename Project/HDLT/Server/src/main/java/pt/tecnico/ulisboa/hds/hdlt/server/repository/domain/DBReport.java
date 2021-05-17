package pt.tecnico.ulisboa.hds.hdlt.server.repository.domain;

import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;

import java.util.HashMap;
import java.util.Map;

public class DBReport {

  private final Location location;
  private final Map<String, byte[]> uIdProofs;
  private final Map<String, Map<String, byte[]>> sIdProofs;

  public DBReport(Location location) {
    this.location = location;
    this.uIdProofs = new HashMap<>();
    this.sIdProofs = new HashMap<>();
  }

  public Location getLocation() {
    return this.location;
  }

  public void putUserIdProof(String uSigner, byte[] uSignedProof) {
    this.uIdProofs.put(uSigner, uSignedProof);
  }

  public Map<String, byte[]> getUserIdProofs() {
    return this.uIdProofs;
  }

  public void putServerIdProof(String uSigner, String sSigner, byte[] sSignedProof) {
    this.sIdProofs.putIfAbsent(uSigner, new HashMap<>());
    this.sIdProofs.get(uSigner).put(sSigner, sSignedProof);
  }

  public Map<String, Map<String, byte[]>> getServerIdProofs() {
    return this.sIdProofs;
  }
}
