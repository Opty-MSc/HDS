package pt.tecnico.ulisboa.hds.hdlt.byzantine.api;

import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import pt.tecnico.ulisboa.hds.hdlt.contract.cs.ClientServerServicesOuterClass.Proof;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesGrpc;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesGrpc.UserUserServicesBlockingStub;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesOuterClass.Header;
import pt.tecnico.ulisboa.hds.hdlt.contract.uu.UserUserServicesOuterClass.RequestULProofReq;
import pt.tecnico.ulisboa.hds.hdlt.lib.common.Location;
import pt.tecnico.ulisboa.hds.hdlt.lib.crypto.Crypto;
import pt.tecnico.ulisboa.hds.hdlt.user.api.RequestULCallback;
import pt.tecnico.ulisboa.hds.hdlt.user.api.SyncProofs;
import pt.tecnico.ulisboa.hds.hdlt.user.api.UserCrypto;
import pt.tecnico.ulisboa.hds.hdlt.user.location.GridManager;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class ByzantineUserToUserFrontend {

  private static final Integer TIMEOUT = 30;
  private final String uname;
  private final GridManager grid;
  private final UserCrypto uCrypto;
  private final Integer nByzantineUsers;
  private final Integer uMaxDistance;
  private final Map<String, ManagedChannel> channels;
  private final Map<String, UserUserServicesGrpc.UserUserServicesFutureStub> stubs;

  public ByzantineUserToUserFrontend(
      String uname,
      GridManager grid,
      UserCrypto uCrypto,
      Integer nByzantineUsers,
      Integer uMaxDistance,
      Map<String, String> uURLs) {
    this.uname = uname;
    this.grid = grid;
    this.uCrypto = uCrypto;
    this.nByzantineUsers = nByzantineUsers;
    this.uMaxDistance = uMaxDistance;
    this.channels = new HashMap<>();
    this.stubs = new HashMap<>();
    ManagedChannel channel;
    for (Map.Entry<String, String> uURL : uURLs.entrySet()) {
      channel = ManagedChannelBuilder.forTarget(uURL.getValue()).usePlaintext().build();
      this.channels.put(uURL.getKey(), channel);
      this.stubs.put(uURL.getKey(), UserUserServicesGrpc.newFutureStub(channel));
    }
  }

  public Map<String, byte[]> getIdProofsImpersonation(Integer epoch) {
    List<String> closeUsersUnames = this.grid.getCloseUsers(epoch, this.uMaxDistance);

    Location location = this.grid.getLocation(this.uname, epoch);
    byte[] proof =
        Proof.newBuilder()
            .setUname(this.uname)
            .setEpoch(epoch)
            .setX(location.getX())
            .setY(location.getY())
            .build()
            .toByteArray();

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

    SyncProofs syncProofs = new SyncProofs();
    for (String closeUsersUname : closeUsersUnames) {
      Futures.addCallback(
          this.stubs.get(closeUsersUname).requestULProof(req),
          new RequestULCallback(closeUsersUname, this.uCrypto, syncProofs, proof, nonce),
          Executors.newSingleThreadExecutor());
    }

    synchronized (syncProofs) {
      while (syncProofs.getNrOfReplies() != closeUsersUnames.size()) {
        try {
          syncProofs.wait();
        } catch (InterruptedException ignored) {
        }
      }
      return new HashMap<>(syncProofs.getIdProofs());
    }
  }

  public Map<String, byte[]> getSelfGeneratedIdProofs(Integer epoch) {
    Location location = this.grid.getLocation(this.uname, epoch);
    byte[] proof =
        Proof.newBuilder()
            .setUname(this.uname)
            .setEpoch(epoch)
            .setX(location.getX())
            .setY(location.getY())
            .build()
            .toByteArray();

    System.out.println("Forging Proof (Self Sign)");
    // Make Valid Proof Myself
    Map<String, byte[]> idProofs = new HashMap<>();
    byte[] signedProof = this.uCrypto.signPayload(proof);
    for (int i = 1; i <= this.nByzantineUsers; i++) {
      idProofs.put(String.format("User%d", i), signedProof);
    }
    return idProofs;
  }

  public Map<String, byte[]> getReplicatedIdProofs(Integer epoch, String uname) {
    Location location = this.grid.getLocation(this.uname, epoch);
    byte[] proof =
        Proof.newBuilder()
            .setUname(this.uname)
            .setEpoch(epoch)
            .setX(location.getX())
            .setY(location.getY())
            .build()
            .toByteArray();

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

    SyncProofs syncProofs = new SyncProofs();
    Futures.addCallback(
        this.stubs.get(uname).requestULProof(req),
        new RequestULCallback(uname, this.uCrypto, syncProofs, proof, nonce),
        Executors.newSingleThreadExecutor());

    synchronized (syncProofs) {
      while (syncProofs.getNrOfReplies() != 1) {
        try {
          syncProofs.wait();
        } catch (InterruptedException ignored) {
        }
      }
      if (syncProofs.getNrOfValidReplies() > 0) {
        System.out.println("Replicating Valid Proofs");
        // Replicate Valid Replies (Possibly Made By Other Byzantine Users)
        byte[] signedProof = syncProofs.getIdProofs().values().iterator().next();
        for (int i = 1; i <= this.nByzantineUsers; i++) {
          if (!syncProofs.getIdProofs().containsKey(String.format("User%d", i))) {
            syncProofs.getIdProofs().put(String.format("User%d", i), signedProof);
          }
        }
        return new HashMap<>(syncProofs.getIdProofs());
      }
      return null;
    }
  }

  public void getIdProofsInvalidSignature(Integer epoch, String uURL) {
    ManagedChannel channel = ManagedChannelBuilder.forTarget(uURL).usePlaintext().build();
    UserUserServicesGrpc.UserUserServicesBlockingStub stub =
        UserUserServicesGrpc.newBlockingStub(channel);

    BigInteger nonce = Crypto.generateRandomNonce();
    Header header =
        Header.newBuilder()
            .setUname(this.uname)
            .setEpoch(epoch)
            .setNonce(ByteString.copyFrom(nonce.toByteArray()))
            .build();
    byte[] signature = this.uCrypto.signPayload(header.toByteArray());
    // Invalid Signature (Added Header 2 Times)
    RequestULProofReq req =
        RequestULProofReq.newBuilder()
            .setHeader(header)
            .setSignature(ByteString.copyFrom(Bytes.concat(signature, header.toByteArray())))
            .build();
    stub.requestULProof(req);
  }

  public void getIdProofsFromFarAwayUser(Integer epoch, String uURL) {
    ManagedChannel channel = ManagedChannelBuilder.forTarget(uURL).usePlaintext().build();
    UserUserServicesBlockingStub stub = UserUserServicesGrpc.newBlockingStub(channel);

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
    stub.requestULProof(req);
  }

  public byte[] getIdProofsFromUser(Integer epoch, String uURL) {
    ManagedChannel channel = ManagedChannelBuilder.forTarget(uURL).usePlaintext().build();
    UserUserServicesBlockingStub stub = UserUserServicesGrpc.newBlockingStub(channel);

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
    return stub.requestULProof(req).getSignedProof().toByteArray();
  }

  public void getMalformedExceptionIdProofs(Integer epoch, String uURL, String uname) {
    ManagedChannel channel = ManagedChannelBuilder.forTarget(uURL).usePlaintext().build();
    UserUserServicesBlockingStub stub = UserUserServicesGrpc.newBlockingStub(channel);

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
    try {
      stub.requestULProof(req);
    } catch (StatusRuntimeException e) {
      this.uCrypto.checkErrorAuth(uname, e, nonce);
    } finally {
      channel.shutdown();
    }
  }

  public void shutdown() {
    this.channels.values().forEach(ManagedChannel::shutdown);
  }
}
