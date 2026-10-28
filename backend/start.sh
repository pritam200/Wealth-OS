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
# Run from a copy so a `mvn package` during development can't swap classes out from under the
# running process (that shows up as NoClassDefFoundError mid-sync). The copy lives outside
# target/ so `mvn clean` can't delete it either. Restart to pick up a build.
JAR="$(ls "$DIR/target/"*.jar | head -1)"
RUN_JAR="$DIR/.run/app.jar"
mkdir -p "$DIR/.run" && cp "$JAR" "$RUN_JAR"
exec "$JAVA_HOME/bin/java" \
  -Djavax.net.ssl.trustStore="$DIR/truststore.p12" \
  -Djavax.net.ssl.trustStoreType=PKCS12 \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -jar "$RUN_JAR" "$@"
