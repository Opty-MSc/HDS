package pt.tecnico.ulisboa.hds.hdlt.user.api;

import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Proof;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesGrpc;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesGrpc.UserUserServicesFutureStub;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesOuterClass.Header;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesOuterClass.RequestULProofReq;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.user.error.UserRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static pt.tecnico.ulisboa.hds.hdlt.lib.common.Common.sleep;

public class UserToUserFrontend {

  private final int callTimeout;
  private final int maxNRetries;
  private final String uname;
  private final GridManager grid;
  private final UserCrypto uCrypto;
  private final Integer nByzantineUsers;
  private final Integer uMaxDistance;
  private final Map<String, ManagedChannel> channels;
  private final Map<String, UserUserServicesFutureStub> stubs;

  public UserToUserFrontend(
      String uname,
      GridManager grid,
      UserCrypto uCrypto,
      Integer nByzantineUsers,
      Integer uMaxDistance,
      Map<String, String> uURLs,
      int callTimeout,
      int maxNRetries) {
    this.uname = uname;
    this.grid = grid;
    this.uCrypto = uCrypto;
    this.nByzantineUsers = nByzantineUsers;
    this.uMaxDistance = uMaxDistance;
    this.callTimeout = callTimeout;
    this.maxNRetries = maxNRetries;
    this.channels = new HashMap<>();
    this.stubs = new HashMap<>();
    for (Map.Entry<String, String> uURL : uURLs.entrySet()) {
      ManagedChannel channel =
          ManagedChannelBuilder.forTarget(uURL.getValue()).usePlaintext().build();
      this.channels.put(uURL.getKey(), channel);
      this.stubs.put(uURL.getKey(), UserUserServicesGrpc.newFutureStub(channel));
    }
  }

  public Map<String, byte[]> getIdProofs(Integer epoch) {
    List<String> closeUsersUnames = this.grid.getCloseUsers(epoch, this.uMaxDistance);
    if (closeUsersUnames.size() < this.nByzantineUsers) return null;

    Location location = this.grid.getLocation(this.uname, epoch);
    byte[] proof =
        Proof.newBuilder()
            .setUname(this.uname)
            .setEpoch(epoch)
            .setX(location.getX())
            .setY(location.getY())
            .build()
            .toByteArray();

    SyncProofs syncProofs = new SyncProofs();
    Map<String, byte[]> idProofs;
    int nRetries = 0;
    do {
      for (String uname : syncProofs.getIdProofs().keySet()) {
        closeUsersUnames.remove(uname);
      }
      syncProofs.resetNrOfReplies();
      idProofs = this.doGetIdProofs(epoch, closeUsersUnames, proof, syncProofs);
      if (idProofs == null && nRetries++ >= maxNRetries) {
        throw new UserRuntimeException("Not Enough User Proofs Gathered!");
      }
    } while (idProofs == null);
    return idProofs;
  }

  public Map<String, byte[]> doGetIdProofs(
      Integer epoch, List<String> closeUsersUnames, byte[] proof, SyncProofs syncProofs) {

    BigInteger nonce = Crypto.generateRandomNonce();
    Header header =
        Header.newBuilder()
            .setUname(this.uname)
            .setEpoch(epoch)
            .setNonce(ByteString.copyFrom(nonce.toByteArray()))
            .build();
    byte[] signature = this.uCrypto.signPayload(header.toByteArray());
    RequestULProofReq req =
        RequestULProofReq.newBuilder()
            .setHeader(header)
            .setSignature(ByteString.copyFrom(signature))
            .build();

    for (String closeUsersUname : closeUsersUnames) {
      Futures.addCallback(
          this.stubs
              .get(closeUsersUname)
              .withDeadlineAfter(callTimeout, TimeUnit.SECONDS)
              .requestULProof(req),
          new RequestULCallback(closeUsersUname, this.uCrypto, syncProofs, proof, nonce),
          Executors.newSingleThreadExecutor());
    }
    synchronized (syncProofs) {
      while (syncProofs.getNrOfValidReplies() < this.nByzantineUsers) {
        // Missing Answers + Approved Answers < Nr of Byzantine Users
        if ((closeUsersUnames.size() - syncProofs.getNrOfReplies())
                + syncProofs.getNrOfValidReplies()
            < this.nByzantineUsers) {
          sleep(1000);
          return null;
        }
        try {
          syncProofs.wait();
        } catch (InterruptedException ignored) {
        }
      }
      return new HashMap<>(syncProofs.getIdProofs());
    }
  }

  public void shutdown() {
    this.channels.values().forEach(ManagedChannel::shutdown);
  }
}
