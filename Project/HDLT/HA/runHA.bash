#!/bin/bash
NByzantineServers=$1
NByzantineUsers=$2
MaxDistance=$3
SessionTime=$4

CMD="mvn exec:java -DmyNByzantineServers=\"${NByzantineServers}\" -DmyNByzantineUsers=\"${NByzantineUsers}\" -DmyMaxDistance=\"${MaxDistance}\" -DmySessionTime=\"${SessionTime}\" 2>/dev/null"
gnome-terminal -- bash -c "${CMD}"
