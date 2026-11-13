#!/bin/sh
# Creates a self-signed code signing identity for krtc-meet in the login keychain, once per Mac.
#
# Why: macOS keeps the Screen Recording, Camera and Microphone permissions per code signature. An ad-hoc signed
# build (the default) gets a new signature on every build, so macOS asks again after each rebuild. Signed with this
# identity, every build has the same designated requirement and keeps its permissions.
#
#   tools/macos/create-signing-identity.sh            # creates "krtc-meet local signing" (asks for your password)
#   ./gradlew :krtc-meet:createDistributable -Pkrtc.macos.signIdentity="krtc-meet local signing"
#
# Or put krtc.macos.signIdentity=krtc-meet local signing into ~/.gradle/gradle.properties.
# For builds you give to other people, use an Apple "Developer ID Application" identity instead (paid account):
# only that one, notarized, opens without warnings on other Macs.
set -eu
NAME="${1:-krtc-meet local signing}"
if security find-certificate -c "$NAME" >/dev/null 2>&1; then
    echo "\"$NAME\" already exists"
    exit 0
fi
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
cat > "$DIR/cert.cnf" <<CNF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $NAME
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
CNF
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -config "$DIR/cert.cnf" -keyout "$DIR/key.pem" -out "$DIR/cert.pem" 2>/dev/null
PASS="$(openssl rand -hex 16)"
openssl pkcs12 -export -legacy -inkey "$DIR/key.pem" -in "$DIR/cert.pem" -name "$NAME" -out "$DIR/identity.p12" -passout "pass:$PASS" 2>/dev/null \
    || openssl pkcs12 -export -inkey "$DIR/key.pem" -in "$DIR/cert.pem" -name "$NAME" -out "$DIR/identity.p12" -passout "pass:$PASS"
security import "$DIR/identity.p12" -k "$HOME/Library/Keychains/login.keychain-db" -P "$PASS" -T /usr/bin/codesign
# Trusting it for code signing makes `security find-identity -p codesigning` list it (asks for your password).
security add-trusted-cert -r trustRoot -p codeSign -k "$HOME/Library/Keychains/login.keychain-db" "$DIR/cert.pem"
echo "created \"$NAME\"; sign builds with -Pkrtc.macos.signIdentity=\"$NAME\""
