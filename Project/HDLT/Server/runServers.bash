#!/bin/bash
NByzantineServers=$1
NByzantineUsers=$2
SessionTime=$3
ServerPort=8080

for ((i = 1; i <= 3 * NByzantineServers + 1; i++)); do
  ServerName="Server${i}"
  cd src/main/resources || exit
  # Cleaning & Starting DB
  sh newDB.sh "${ServerName}"
  cd - || exit
  CMD="mvn exec:java -DmyServerName=\"${ServerName}\" -DmyServerPort=\"${ServerPort}\" -DmyNByzantineServers=\"${NByzantineServers}\" -DmyNByzantineUsers=\"${NByzantineUsers}\" -DmySessionTime=\"${SessionTime}\" 2>/dev/null"
  gnome-terminal -- bash -c "${CMD}"
  ((ServerPort++))
done
