#!/bin/bash
NUsers=$1
NServers=$2
KSAlias=$3
KSPwd=$4

rm -rf UserKeyStores UserCertificates
mkdir UserKeyStores UserCertificates

echo "Generating KeyStores & Certificates of Users!"

for ((i = 1; i <= NUsers; i++)); do
  keytool -genkeypair \
    -alias "${KSAlias}User${i}" \
    -dname "CN=localhost" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 365 \
    -storepass "${KSPwd}User${i}" \
    -keystore "User${i}.jks"

  keytool -exportcert \
    -file "User${i}.crt" \
    -keystore "User${i}.jks" \
    -storepass "${KSPwd}User${i}" \
    -alias "${KSAlias}User${i}"

  mv "User${i}.jks" UserKeyStores
  mv "User${i}.crt" UserCertificates
done

rm -rf ServerKeyStores ServerCertificates
mkdir ServerKeyStores ServerCertificates

echo "Generating KeyStores & Certificates of Servers!"

for ((i = 1; i <= NServers; i++)); do
  keytool -genkeypair \
    -alias "${KSAlias}Server${i}" \
    -dname "CN=localhost" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 365 \
    -storepass "${KSPwd}Server${i}" \
    -keystore "Server${i}.jks"

  keytool -exportcert \
    -file "Server${i}.crt" \
    -keystore "Server${i}.jks" \
    -storepass "${KSPwd}Server${i}" \
    -alias "${KSAlias}Server${i}"

  mv "Server${i}.jks" ServerKeyStores
  mv "Server${i}.crt" ServerCertificates
done

rm -f HA.jks HA.crt

echo "Generating KeyStores & Certificates of HA!"

keytool -genkeypair \
  -alias "${KSAlias}HA" \
  -dname "CN=localhost" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 365 \
  -storepass "${KSPwd}HA" \
  -keystore "HA.jks"

keytool -exportcert \
  -file "HA.crt" \
  -keystore "HA.jks" \
  -storepass "${KSPwd}HA" \
  -alias "${KSAlias}HA"
