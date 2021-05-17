#!/bin/bash
Host="localhost"
Port=5000

GridFile="Grid.csv"
URLsFile="UsersURLs.csv"

NUsers=$1
NEpochs=$2
GridSize=$3

# Cleanup
rm -rf "${GridFile}" "${URLsFile}"

for ((i = 1; i <= NUsers; i++)); do
  # Generate URLs
  echo "User${i};${Host}:${Port}" >>"${URLsFile}"
  ((Port++))
  # Generate Grid
  for ((e = 1; e <= NEpochs; e++)); do
    echo "User${i};${e};$((RANDOM % GridSize));$((RANDOM % GridSize))" >>"${GridFile}"
  done
done
