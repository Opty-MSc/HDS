package pt.tecnico.ulisboa.hds.hdlt.server.repository.domain;

import java.util.HashMap;
import java.util.Map;

public class DBAuthProof {

  private final DBProof proof;
  private final byte[] uSignedProof;
  private final Map<String, byte[]> sIdProofs;

  public DBAuthProof(DBProof proof, byte[] uSignedProof) {
    this.proof = proof;
    this.uSignedProof = uSignedProof;
    this.sIdProofs = new HashMap<>();
  }

  public DBProof getProof() {
    return this.proof;
  }

  public byte[] getUserSignedProof() {
    return this.uSignedProof;
  }

  public void putServerIdProof(String sSigner, byte[] sSignedProof) {
    this.sIdProofs.put(sSigner, sSignedProof);
  }

  public Map<String, byte[]> getServerIdProofs() {
    return this.sIdProofs;
  }
}
