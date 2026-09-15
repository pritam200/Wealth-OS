#!/usr/bin/env bash
# Rebuilds backend/truststore.p12 = the current JDK's cacerts + corporate roots.
#
# Needed because outbound HTTPS on this network is intercepted by a corporate TLS proxy
# ("Gainsight, Inc. Forward Trust CA"). That root lives in the macOS keychain, not in any JDK
# bundle, so without it every external call fails PKIX validation.
#
# Re-run when: the JDK is upgraded, or the corporate CA rotates.
# Off the corporate network this file isn't needed — drop the -Djavax.net.ssl.* jvmArguments
# from pom.xml instead.
set -euo pipefail

JH="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
CORP_CN="${CORP_CN:-Gainsight}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/truststore.p12"
PASS=changeit

echo "JDK:  $JH"
echo "Out:  $OUT"

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

security find-certificate -a -c "$CORP_CN" -p /System/Library/Keychains/SystemRootCertificates.keychain > "$tmp/corp.pem" 2>/dev/null || true
security find-certificate -a -c "$CORP_CN" -p /Library/Keychains/System.keychain           >> "$tmp/corp.pem" 2>/dev/null || true

python3 - "$tmp" <<'PY'
import re, sys, pathlib
d = pathlib.Path(sys.argv[1])
pem = (d / 'corp.pem').read_text() if (d / 'corp.pem').exists() else ''
certs, seen = re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', pem, re.S), set()
n = 0
for c in certs:
    if c in seen: continue
    seen.add(c); (d / f'corp-{n}.pem').write_text(c + "\n"); n += 1
print(f"{n} unique corporate cert(s)")
PY

rm -f "$OUT"
"$JH/bin/keytool" -importkeystore -srckeystore "$JH/lib/security/cacerts" -srcstorepass "$PASS" \
  -destkeystore "$OUT" -deststoretype PKCS12 -deststorepass "$PASS" -noprompt 2>&1 | tail -1

i=0
for f in "$tmp"/corp-*.pem; do
  [ -e "$f" ] || continue
  cn=$(openssl x509 -in "$f" -noout -subject 2>/dev/null | sed 's/.*CN *= *//' | tr -cd 'A-Za-z0-9')
  "$JH/bin/keytool" -importcert -file "$f" -alias "corp-${cn}-$i" \
    -keystore "$OUT" -storetype PKCS12 -storepass "$PASS" -noprompt >/dev/null 2>&1 && i=$((i+1))
done

echo "imported $i corporate root(s); total entries: $("$JH/bin/keytool" -list -keystore "$OUT" -storetype PKCS12 -storepass "$PASS" 2>/dev/null | grep -c trustedCertEntry)"
