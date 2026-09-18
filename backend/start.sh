#!/bin/bash
# Start the backend with the project-local truststore (JDK cacerts + corporate root CA).
# This network intercepts outbound HTTPS via a corporate TLS proxy, so calls to Google APIs,
# AMFI India, etc. fail PKIX validation without it — see tools/regen-truststore.sh.
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
if [ ! -f "$DIR/truststore.p12" ]; then
  echo "truststore.p12 missing — run backend/tools/regen-truststore.sh first" >&2
  exit 1
fi
exec "$JAVA_HOME/bin/java" \
  -Djavax.net.ssl.trustStore="$DIR/truststore.p12" \
  -Djavax.net.ssl.trustStoreType=PKCS12 \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -jar "$DIR/target/"*.jar "$@"
