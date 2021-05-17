cd Server/src/main/resources || exit
sh newDB.sh Server1
cd ../../../ || exit
echo "Starting Server (...)"
mvn exec:java -DmyNByzantineUsers=0 -DmyNByzantineServers=0 >/dev/null 2>&1 &
ServerPID=$!
cd ../User || exit
sleep 5
echo "Starting User (...)"
echo 4 | mvn exec:java -DmyNByzantineUsers=0 -DmyNByzantineServers=0 &
UserPID=$!
sleep 5
echo "Killing Server (...)"
kill "${ServerPID}"
cd ../Server || exit
sleep 1
echo "Starting Server Again (...)"
mvn exec:java -DmyNByzantineUsers=0 -DmyNByzantineServers=0 >/dev/null 2>&1 &
ServerPID=$!
wait ${UserPID}
cd ../User || exit
echo "Starting User Again (...)"
sleep 1
echo 4 | mvn exec:java -DmyNByzantineUsers=0 -DmyNByzantineServers=0
kill "${ServerPID}"
