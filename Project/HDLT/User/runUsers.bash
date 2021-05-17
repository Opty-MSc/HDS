#!/bin/bash
NUsers=$1
NByzantineUsers=$2
NByzantineServers=$3
MaxDistance=$4
SessionTime=$5
EpochLifeTime=$6

for ((i = 1; i <= NUsers; i++)); do
  CMD="mvn exec:java -DmyUsername=\"User${i}\" -DmyNByzantineUsers=\"${NByzantineUsers}\" -DmyNByzantineServers=\"${NByzantineServers}\" -DmyMaxDistance=\"${MaxDistance}\" -DmySessionTime=\"${SessionTime}\" -DmyEpochLifeTime=\"${EpochLifeTime}\""
  gnome-terminal -- bash -c "${CMD}"
done
