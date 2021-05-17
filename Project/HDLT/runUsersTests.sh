# Launching Server
gnome-terminal -- bash -c "
cd Server;
bash runServers.bash 1 1 60 >/dev/null;
"

# Running Users Tests
gnome-terminal -- bash -c "
cd User;
read -p \"Press Enter to Run Users Tests!\";
mvn clean test 2>/dev/null;
read -p \"Press Enter to Exit!\";
"
