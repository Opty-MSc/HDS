# Launching Server
gnome-terminal -- bash -c "
cd Server;
bash runServers.bash 1 2 60 >/dev/null;
"

# Running Byzantine Tests
gnome-terminal -- bash -c "
cd Byzantine;
read -p \"Press Enter to Run Byzantine Tests!\";
mvn clean test 2>/dev/null;
read -p \"Press Enter to Exit!\";
"
