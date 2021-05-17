package pt.tecnico.ulisboa.hds.hdlt.server.repository.domain;

import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;

public class DBProof {

  private final String uname;
  private final int epoch;
  private final Location location;

  public DBProof(String uname, int epoch, Location location) {
    this.uname = uname;
    this.epoch = epoch;
    this.location = location;
  }

  public String getUname() {
    return this.uname;
  }

  public int getEpoch() {
    return this.epoch;
  }

  public Location getLocation() {
    return this.location;
  }
}
