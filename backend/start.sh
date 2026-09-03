#!/bin/bash
# Start the backend with the project-local truststore (macOS root CAs).
# JDK 8u301's bundled CA certs are outdated and can't verify modern SSL
# certificates from Google APIs, AMFI India, etc.
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_301.jdk/Contents/Home
exec "$JAVA_HOME/bin/java" \
  -Djavax.net.ssl.trustStore="$DIR/truststore.jks" \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -jar "$DIR/target/"*.jar "$@"
