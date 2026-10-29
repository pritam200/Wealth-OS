#!/bin/bash
# Start the backend. On the corporate network outbound HTTPS goes through a TLS proxy, so calls
# to Google APIs, AMFI India, etc. fail PKIX validation unless the project-local truststore
# (JDK cacerts + corporate root CA, see tools/regen-truststore.sh) is used. Off that network
# the JDK's own trust store is right, so a missing truststore.p12 is skipped.
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null)" || { echo "Java 25 not found (brew install openjdk@25)" >&2; exit 1; }
fi
TRUST=()
if [ -f "$DIR/truststore.p12" ]; then
  TRUST=(-Djavax.net.ssl.trustStore="$DIR/truststore.p12" -Djavax.net.ssl.trustStoreType=PKCS12
         -Djavax.net.ssl.trustStorePassword=changeit)
else
  echo "truststore.p12 not found: using the JDK's trust store (on the corporate network, run tools/regen-truststore.sh)" >&2
fi
# Run from a copy so a `mvn package` during development can't swap classes out from under the
# running process (that shows up as NoClassDefFoundError mid-sync). The copy lives outside
# target/ so `mvn clean` can't delete it either. Restart to pick up a build.
JAR="$(ls "$DIR/target/"*.jar 2>/dev/null | head -1)"
[ -n "$JAR" ] || { echo "No jar in target/: build first with  mvn -q package -DskipTests" >&2; exit 1; }
RUN_JAR="$DIR/.run/app.jar"
mkdir -p "$DIR/.run" && cp "$JAR" "$RUN_JAR"
exec "$JAVA_HOME/bin/java" "${TRUST[@]}" -jar "$RUN_JAR" "$@"
