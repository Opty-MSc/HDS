# Highly Dependable Location Tracker

### Requirements

- Maven
- Java 15
- PostgreSQL 12

## Base Setup

```shell
$ mvn clean install -DskipTests
```

---

## To Run Servers

```shell
$ cd Server
$ bash runServers.bash <NByzantineServers> <NByzantineUsers> <SessionTime (Seconds)>
```

- The Script Automatically Cleans the Databases of Each Server

### Suggested Parameters

- `<NByzantineServers>`: 1
- `<NByzantineUsers>`: 1
- `<SessionTime (Seconds)>`: 60

## To Run Users

- To Run *N* Users, *N* <= 10:

```shell
$ cd User
$ bash runUsers.bash <NUsers> <NByzantineUsers> <NByzantineServers> <MaxDistance> <SessionTime (Seconds)> <EpochLifeTime (Millis)>
```

### Suggested Parameters

- `<NUsers>`: 10
- `<NByzantineUsers>`: 1
- `<NByzantineServers>`: 1
- `<MaxDistance>`: 5
- `<SessionTime (Seconds)>`: 60
- `<EpochLifeTime (Millis)>`: 1000
  (`-1` To Automatically Open the User Shell Without Submitting its Locations)

## To Run HA

```shell
$ cd HA
$ bash runHA.bash <NByzantineServers> <NByzantineUsers> <MaxDistance> <SessionTime (Seconds)>
```

### Suggested Parameters

- `<NByzantineServers>`: 1
- `<NByzantineUsers>`: 1
- `<MaxDistance>`: 5
- `<SessionTime (Seconds)>`: 60

---

## Note

- Additional Parameters can be Modified in POMs:
- `<PowDifficulty>`: Number of Leading Zeros in PoW
- `<CallTimeout (Seconds)>`: Timeout of Stub Call
- `<MaxNRetries>`: Maximum Number of Retries in Stub Calls

---

## To Run Tests

### To Run User Tests

```shell
$ sh runUsersTests.sh
```

#### Instructions

1) Please, Insert your Password in the Terminal that is Requesting it. (The Script to Create a New Database
   Requires `sudo`). This Terminal Will Run the Servers!
2) Please, Press *Enter* in the Terminal Displaying *Press Enter to Run Users Tests!* after the Server has Initialized!
   This Terminal Will Run the Users Tests!

### To Run HA Tests

```shell
$ sh runHATests.sh
```

#### Instructions

1) Please, Insert your Password in the Terminal that is Requesting it. (The Script to Create a New Database
   Requires `sudo`). This Terminal Will Run the Servers!
2) Please, Press *Enter* in the Terminal Displaying *Press Enter to Launch Users!* after the Server has Initialized! It
   Will Open 10 User Processes Submitting its Location in Multiple Epochs.
3) Please, Press *Enter* in the Terminal Displaying *Press Enter to Run HA Tests!* after all Users Submit its Location
   Proofs. (It Takes Around 45 Seconds due to ADEB & PoW Expensive Computation). This Terminal Will Run the HA Tests.

### To Run Byzantine Tests

```shell
$ sh runByzantineTests.sh
```

#### Instructions

1) Please, Insert your Password in the Terminal that is Requesting it. (The Script to Create a New Database
   Requires `sudo`). This Terminal Will Run the Servers!
2) Please, Press *Enter* in the Terminal Displaying *Press Enter to Run Byzantine Tests!* after the Server has
   Initialized! This Terminal Will Run the Byzantine Tests.

### To Run Data Persistence Test

```shell
$ sh runPersistenceTest.sh
```

#### Test Description

The Script:

1) Runs the Server.
2) Lets a User Submit its Location Proofs.
3) Kills the Server.
4) Runs the Server Again.
5) Lets the Same User to Submit Again its Location Proofs.
   (The Server Already Have Them All!)

### To Run Data Corruption Test

```shell
$ sh runCorruptionTest.sh
```

#### Test Description

The Script:

1) Runs the Server.
2) Lets a User Submit a few Location Proofs.
3) Kills the Server.
   (While the User is Submitting its Location Proofs!)
4) The User Keeps Retrying the Submission.
5) Runs the Server Again.
6) Lets the User Submit its Remaining Location Proofs.
7) Kills the Server.
8) Runs the Server Again.
9) Lets the Same User to Submit Again its Location Proofs.
   (The Server Already Have Them All!)

---

## Tests Categories

### HA Valid Tests

- [`obtainUL`](./HA/src/test/java/pt/tecnico/ulisboa/hds/hdlt/ha/HAToServerTests.java#L85)
- [`obtainULNotPresent`](./HA/src/test/java/pt/tecnico/ulisboa/hds/hdlt/ha/HAToServerTests.java#L101)
- [`obtainUAtL`](./HA/src/test/java/pt/tecnico/ulisboa/hds/hdlt/ha/HAToServerTests.java#L108)

### User Valid Tests

- [`submitUL`](./User/src/test/java/pt/tecnico/ulisboa/hds/hdlt/user/UserToServerTests.java#L96)
- [`submitULWrittenInByzantineQuorum`](./User/src/test/java/pt/tecnico/ulisboa/hds/hdlt/user/UserToServerTests.java#L113)
- [`submitULThatAlreadyExists`](./User/src/test/java/pt/tecnico/ulisboa/hds/hdlt/user/UserToServerTests.java#L144)
- [`obtainUL`](./User/src/test/java/pt/tecnico/ulisboa/hds/hdlt/user/UserToServerTests.java#L135)
- [`obtainULNotPresent`](./User/src/test/java/pt/tecnico/ulisboa/hds/hdlt/user/UserToServerTests.java#L163)
- [`requestMyProofs`](./User/src/test/java/pt/tecnico/ulisboa/hds/hdlt/user/UserToServerTests.java#L173)

### Freshness Tests

- [`dHNoFreshness`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToDHServerAuthFreshTests.java#L47)
- [`submitULReportFreshness`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerAuthFreshTests.java#L63)
- [`obtainULNoFreshness`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerAuthFreshTests.java#L89)
- [`requestMyProofsNoFreshness`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerAuthFreshTests.java#L105)
- [`echoNoFreshness`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineServerToServerAuthFreshTests.java#L69)
- [`readyNoFreshness`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineServerToServerAuthFreshTests.java#L92)

### Authenticity Tests

- [`dHNoValidSignature`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToDHServerAuthFreshTests.java#L54)
- [`submitULReportNoValidHMAC`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerAuthFreshTests.java#L76)
- [`obtainULNoValidHMAC`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerAuthFreshTests.java#L97)
- [`requestMyProofsNoValidHMAC`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerAuthFreshTests.java#L114)
- [`getIdProofsInvalidSignature`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToUserAuthFreshTests.java#L67)
- [`echoNoValidHMAC`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineServerToServerAuthFreshTests.java#L80)
- [`readyNoValidHMAC`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineServerToServerAuthFreshTests.java#L105)

### Invalid Session Tests

- [`submitULReportWithInvalidSession`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L327)
- [`obtainULWithInvalidSession`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L349)
- [`echoWithInvalidSession`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineServerToServerTests.java#L183)
- [`readyWithInvalidSession`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineServerToServerTests.java#L201)

### Dropping Packets Tests

- [`submitULReportDoesNotAnswer`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/MITMByzantineUserToServerTests.java#L126)
- [`obtainULDoesNotAnswer`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/MITMByzantineUserToServerTests.java#L161)
- [`requestULProofDoesNotAnswer`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/MITMByzantineUserToUserTests.java#L116)

### Rejecting Packets Tests

- [`submitULReportWhereServerReject`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/MITMByzantineUserToServerTests.java#L108)
- [`obtainULWhereServerReject`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/MITMByzantineUserToServerTests.java#L144)
- [`requestULMalformedExceptionProof`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/MITMByzantineUserToUserTests.java#L96)

### Spam Mechanism Tests

- [`submitULReportInvalidPow`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L293)
- [`obtainULWriteBackInvalidPow`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L310)

### Forging Location Proofs / Reports Tests

- [`submitULSelfGeneratedProofs`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L179)
- [`submitULReplicatedProofs`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L196)
- [`submitULWithoutProofs`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L215)
- [`submitULWithInsufficientProofs`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L230)
- [`submitDiffULReportsToDiffServers`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L368)
- [`obtainULWriteBackWithoutUserProofs`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L250)
- [`obtainULWriteBackWithoutServerProofs`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L269)
- [`requestULProofToFarAwayUser`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToUserTests.java#L91)
- [`echoInvalidUserReport`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineServerToServerTests.java#L133)
- [`readyInvalidUserReport`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineServerToServerTests.java#L148)
- [`readyInvalidReport`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineServerToServerTests.java#L164)

### Impersonating Tests

- [`submitULImpersonator`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L159)
- [`obtainULImpersonator`](./Byzantine/src/test/java/pt/tecnico/ulisboa/hds/hdlt/byzantine/ByzantineUserToServerTests.java#L149)

### Data Persistence Test

- [`runPersistenceTest`](./runPersistenceTest.sh)

### Data Corruption Test

- [`runCorruptionTest`](./runCorruptionTest.sh)

---

## Useful Scripts

- To Get the List of Users in the Proximity Considering a Given Max Distance:

```shell
$ cd User
$ python3 getCloseUsers.py src/main/resources/Grid.csv <MaxDistance>
```
