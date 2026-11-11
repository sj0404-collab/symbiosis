#!/usr/bin/env bash
# CI-only certificate fixtures, issued by the disposable root from run.sh.
# Do not ship certificates or private keys in an APK or upload to artifacts.
set -Eeuo pipefail
: "${1:?work dir}" "${2:?root cert}" "${3:?root key}"
D=$1 ROOT_CERT=$2 ROOT_KEY=$3
mkdir -p "$D/newcerts"
: > "$D/index.txt"
printf '01\n' > "$D/serial"
cat > "$D/ca.cnf" <<EOF_CONFIG
[ ca ]
default_ca = local_ca
[ local_ca ]
dir = $D
database = \$dir/index.txt
new_certs_dir = \$dir/newcerts
certificate = $ROOT_CERT
private_key = $ROOT_KEY
serial = \$dir/serial
default_md = sha256
default_days = 1
policy = common_policy
unique_subject = no
[ common_policy ]
commonName = supplied
[ server_ext ]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost
[ intermediate_ext ]
basicConstraints = critical,CA:TRUE,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
[ client_ext ]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = clientAuth
subjectAltName = DNS:localhost
EOF_CONFIG
openssl req -new -newkey rsa:2048 -nodes -keyout "$D/server-key.pem" -out "$D/server.csr" -subj '/CN=localhost' >/dev/null 2>&1
for variant in server expired client; do
  ext=server_ext
  date_options=()
  if [ "$variant" = expired ]; then date_options=(-startdate 200101000000Z -enddate 200201000000Z); fi
  if [ "$variant" = client ]; then ext=client_ext; fi
  openssl ca -batch -notext -config "$D/ca.cnf" -extensions "$ext" "${date_options[@]}" \
    -in "$D/server.csr" -out "$D/$variant.pem" >/dev/null 2>&1
done
# A real chain with a separately signed intermediate. Same leaf/key is used
# for both cases; only the server-provided intermediate is changed.
openssl req -new -newkey rsa:2048 -nodes -keyout "$D/intermediate-key.pem" -out "$D/intermediate.csr" -subj '/CN=CI intermediate CA' >/dev/null 2>&1
openssl ca -batch -notext -config "$D/ca.cnf" -extensions intermediate_ext \
  -in "$D/intermediate.csr" -out "$D/intermediate.pem" >/dev/null 2>&1
openssl x509 -req -in "$D/server.csr" -CA "$D/intermediate.pem" -CAkey "$D/intermediate-key.pem" -CAcreateserial \
  -days 1 -sha256 -extfile "$D/ca.cnf" -extensions server_ext -out "$D/intermediate-leaf.pem" >/dev/null 2>&1
cat "$D/intermediate-leaf.pem" "$D/intermediate.pem" > "$D/intermediate-chain.pem"
# The fixture must itself prove validity/expiry/purpose before any emulator test.
openssl verify -purpose sslserver -CAfile "$ROOT_CERT" "$D/server.pem" > "$D/verify-server.txt"
if openssl verify -purpose sslserver -CAfile "$ROOT_CERT" "$D/expired.pem" > "$D/verify-expired.txt" 2>&1; then
  echo FIXTURE_EXPIRED_ACCEPTED >&2; exit 1
fi
grep -qi 'certificate has expired' "$D/verify-expired.txt" || { cat "$D/verify-expired.txt" >&2; exit 1; }
if openssl verify -purpose sslserver -CAfile "$ROOT_CERT" "$D/client.pem" > "$D/verify-client.txt" 2>&1; then
  echo FIXTURE_CLIENT_EKU_ACCEPTED >&2; exit 1
fi
grep -qi 'unsuitable certificate purpose' "$D/verify-client.txt" || { cat "$D/verify-client.txt" >&2; exit 1; }
openssl verify -purpose sslserver -CAfile "$ROOT_CERT" -untrusted "$D/intermediate.pem" "$D/intermediate-leaf.pem" > "$D/verify-intermediate.txt"
if openssl verify -purpose sslserver -CAfile "$ROOT_CERT" "$D/intermediate-leaf.pem" > "$D/verify-no-intermediate.txt" 2>&1; then
  echo FIXTURE_MISSING_INTERMEDIATE_ACCEPTED >&2; exit 1
fi
grep -qi 'unable to get local issuer certificate' "$D/verify-no-intermediate.txt" || { cat "$D/verify-no-intermediate.txt" >&2; exit 1; }
echo 'TLS_CHAIN_FIXTURE_OK valid expired client_only intermediate missing_intermediate'
