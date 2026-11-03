#!/bin/sh
# Regenerates the fixed test credentials in this directory. They mirror the extensions that
# BazelBackendCredentials asks keytool for, but stay valid for 100 years so that tests never issue their own.
# The keys protect nothing: they exist only so that tests have valid mTLS material without running keytool.
set -eu
cd "$(dirname "$0")"
days=36500
key() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1-key.pem"; }
key ca
openssl req -new -x509 -key ca-key.pem -subj "/CN=Invocation CA" -days $days -sha256 \
  -addext "basicConstraints=critical,CA:true" -addext "keyUsage=critical,keyCertSign,cRLSign" -out ca.pem
leaf() {
  key "$1"
  openssl req -new -key "$1-key.pem" -subj "/CN=$1" -out "$1.csr"
  printf 'keyUsage=critical,digitalSignature\nextendedKeyUsage=%s\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n' "$2" > "$1.ext"
  openssl x509 -req -in "$1.csr" -CA ca.pem -CAkey ca-key.pem -CAcreateserial -days $days -sha256 \
    -extfile "$1.ext" -out "$1-cert.pem"
  rm "$1.csr" "$1.ext"
}
leaf server serverAuth
leaf worker clientAuth
leaf other-worker clientAuth
rm -f ca.srl
