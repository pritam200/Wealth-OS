#!/bin/sh
# Encrypted secrets for this repo: plaintext env files never enter git, only config/secrets.tar.gpg.
#
#   secrets.sh encrypt       bundle .env, deploy/.env, backend/gmail.env -> config/secrets.tar.gpg
#   secrets.sh decrypt-auto  non-interactive: unpack the bundle into .tmp-secrets/ (0700/0600)
#   secrets.sh clean         delete .tmp-secrets/
#   secrets.sh run CMD...    decrypt-auto, run CMD with the variables exported, then clean (local dev)
#
# The passphrase comes from the DEPLOY_SECRET_KEY environment variable. `encrypt` prompts for it
# on a terminal when the variable is unset; `decrypt-auto` never prompts. It is fed to gpg on
# stdin, so it is not visible in the process list, and it is never written to disk or printed.
# Bundle layout (flat file names inside the archive):
#   .env        <- ./.env
#   deploy.env  <- ./deploy/.env
#   gmail.env   <- ./backend/gmail.env
set -eu
umask 077

ROOT=$(cd "$(dirname "$0")/.." && pwd)
BUNDLE="$ROOT/config/secrets.tar.gpg"
OUT="$ROOT/.tmp-secrets"
# "archive-name:path-relative-to-repo-root"
MAP=".env:.env deploy.env:deploy/.env gmail.env:backend/gmail.env"

die() { echo "secrets.sh: $*" >&2; exit 1; }
have_gpg() { command -v gpg >/dev/null 2>&1 || die "gpg is required (brew install gnupg / apt-get install gnupg)"; }

# Run gpg with the passphrase on stdin (loopback pinentry, no agent prompt, no tty needed).
gpg_pass() { printf '%s' "$KEY" | gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-fd 0 "$@"; }

load_key_or_prompt() {
  KEY=${DEPLOY_SECRET_KEY:-}
  [ -n "$KEY" ] && return 0
  [ -t 0 ] || die "DEPLOY_SECRET_KEY is not set and there is no terminal to ask on"
  printf 'Encryption passphrase: ' >&2; stty -echo; read -r KEY; stty echo; echo >&2
  printf 'Repeat passphrase:     ' >&2; stty -echo; read -r KEY2; stty echo; echo >&2
  [ "$KEY" = "$KEY2" ] || die "passphrases do not match"
  [ -n "$KEY" ] || die "empty passphrase"
}

cmd_encrypt() {
  have_gpg
  load_key_or_prompt
  stage=$(mktemp -d); chmod 700 "$stage"
  tmp_tar=$(mktemp)
  trap 'rm -rf "$stage" "$tmp_tar"' EXIT INT TERM
  n=0
  for pair in $MAP; do
    name=${pair%%:*}; src=${pair#*:}
    if [ -f "$ROOT/$src" ]; then
      chmod 600 "$ROOT/$src"
      cp "$ROOT/$src" "$stage/$name"; chmod 600 "$stage/$name"
      echo "  + $src"; n=$((n + 1))
    else
      echo "  - $src (not present, skipped)"
    fi
  done
  [ "$n" -gt 0 ] || die "none of the secret files exist; nothing to encrypt"
  (cd "$stage" && tar -cf "$tmp_tar" $(ls -A))
  mkdir -p "$ROOT/config"
  rm -f "$BUNDLE"
  gpg_pass --symmetric --cipher-algo AES256 --s2k-mode 3 --s2k-digest-algo SHA512 --s2k-count 65011712 \
      --output "$BUNDLE" "$tmp_tar"
  chmod 644 "$BUNDLE"   # ciphertext, meant to be committed
  echo "Encrypted $n file(s) into config/secrets.tar.gpg. Commit that file; keep the passphrase out of git."
}

cmd_decrypt_auto() {
  have_gpg
  [ -n "${DEPLOY_SECRET_KEY:-}" ] || die "DEPLOY_SECRET_KEY is not set; cannot decrypt (non-interactive)"
  [ -f "$BUNDLE" ] || die "config/secrets.tar.gpg not found; run 'secrets.sh encrypt' and commit it"
  KEY=$DEPLOY_SECRET_KEY
  rm -rf "$OUT"; mkdir -p "$OUT"; chmod 700 "$OUT"
  tmp_tar=$(mktemp)
  trap 'rm -f "$tmp_tar"' EXIT INT TERM
  if ! gpg_pass --decrypt --output "$tmp_tar" "$BUNDLE" 2>/dev/null; then
    rm -rf "$OUT"
    die "decryption failed (wrong DEPLOY_SECRET_KEY or a corrupted bundle)"
  fi
  # Only the three known flat names may be extracted: no paths, no '..', no links.
  for entry in $(tar -tf "$tmp_tar"); do
    case "$entry" in
      .env|deploy.env|gmail.env) ;;
      *) rm -rf "$OUT"; die "unexpected entry '$entry' in the bundle; refusing to extract" ;;
    esac
  done
  tar -xf "$tmp_tar" -C "$OUT"
  rm -f "$tmp_tar"   # the decrypted archive must not outlive this function (cmd_run replaces the trap)
  chmod 700 "$OUT"; chmod 600 "$OUT"/.env "$OUT"/deploy.env "$OUT"/gmail.env 2>/dev/null || true
  echo "Decrypted secrets into .tmp-secrets/ (removed by 'secrets.sh clean')."
}

cmd_clean() { rm -rf "$OUT"; }

cmd_run() {
  [ $# -gt 0 ] || die "usage: secrets.sh run COMMAND [ARGS...]"
  cmd_decrypt_auto >/dev/null
  trap 'rm -rf "$OUT"' EXIT INT TERM
  set -a
  for f in .env deploy.env gmail.env; do [ -f "$OUT/$f" ] && . "$OUT/$f"; done
  set +a
  "$@"
}

case "${1:-}" in
  encrypt) cmd_encrypt ;;
  decrypt-auto) cmd_decrypt_auto ;;
  clean) cmd_clean ;;
  run) shift; cmd_run "$@" ;;
  *) echo "usage: $0 encrypt | decrypt-auto | clean | run CMD..." >&2; exit 2 ;;
esac
