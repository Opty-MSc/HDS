package pt.tecnico.ulisboa.hds.hdlt.server.api.adeb;

import com.google.protobuf.ByteString;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Proof;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.UserReport;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.server.api.ServerCrypto;
import pt.tecnico.ulisboa.hds.hdlt.server.api.adeb.ADEBInstance.ADEBMetadata;
import pt.tecnico.ulisboa.hds.hdlt.server.error.ServerStatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.server.repository.DBManager;

import java.math.BigInteger;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static io.grpc.Status.INTERNAL;
import static pt.tecnico.ulisboa.hds.hdlt.server.api.CommonServices.toByteArray;

public class ADEBInstanceManager {

  private final ServerCrypto sCrypto;
  private final DBManager db;
  private final Map<String, Map<Integer, ADEBInstance>> instances;

  public ADEBInstanceManager(ServerCrypto sCrypto, DBManager db) {
    this.sCrypto = sCrypto;
    this.db = db;
    this.instances = new HashMap<>();
  }

  public synchronized ADEBInstance getInstance(String uname, Integer epoch, BigInteger nonce) {
    if ((!this.instances.containsKey(uname) || !this.instances.get(uname).containsKey(epoch))
        && this.hasDelivered(uname, epoch, nonce)) return null;

    this.instances.putIfAbsent(uname, new HashMap<>());
    this.instances.get(uname).putIfAbsent(epoch, new ADEBInstance());
    return this.instances.get(uname).get(epoch);
  }

  public synchronized void deliver(String uname, Integer epoch, UserReport uReport) {
    ADEBInstance instance = this.instances.get(uname).remove(epoch);
    if (this.instances.get(uname).isEmpty()) this.instances.remove(uname);

    ADEBMetadata metadata = instance.getMetadata(uReport);
    Proof proof = uReport.getProof();
    try {
      this.db.addReport(
          uname,
          epoch,
          new Location(proof.getX(), proof.getY()),
          toByteArray(uReport.getUIdProofsMap()),
          metadata != null ? unwrapSIdProofs(metadata.getSIdProofs()) : new HashMap<>());
    } catch (SQLException e) {
      instance.setThrowable(e);
    }
    instance.deliver();
  }

  public boolean hasDelivered(String uname, Integer epoch, BigInteger nonce) {
    try {
      return this.db.hasReport(uname, epoch);
    } catch (SQLException e) {
      throw new ServerStatusRuntimeException(this.sCrypto, INTERNAL, "Database Error!", nonce);
    }
  }

  private Map<String, Map<String, byte[]>> unwrapSIdProofs(
      Map<String, Map<String, ByteString>> sIdProofs) {
    return sIdProofs.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> toByteArray(e.getValue())));
  }
}
