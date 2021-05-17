cd Server/src/main/resources || exit
sh newDB.sh Server1
cd ../../../ || exit
echo "Starting Server (...)"
mvn exec:java -DmyNByzantineUsers=0 -DmyNByzantineServers=0 >/dev/null 2>&1 &
PID=$!
cd ../User || exit
sleep 5
echo "Starting User (...)"
echo 4 | mvn exec:java -DmyNByzantineUsers=0 -DmyNByzantineServers=0
echo "Killing Server (...)"
kill -9 "${PID}"
cd ../Server || exit
echo "Starting Server Again (...)"
mvn exec:java -DmyNByzantineUsers=0 -DmyNByzantineServers=0 >/dev/null 2>&1 &
PID=$!
cd ../User || exit
sleep 5
echo "Starting User Again (...)"
echo 4 | mvn exec:java -DmyNByzantineUsers=0 -DmyNByzantineServers=0
kill -9 "${PID}"
