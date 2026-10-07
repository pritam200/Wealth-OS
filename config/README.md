# Encrypted config

`secrets.tar.gpg` holds `.env`, `deploy/.env` and `backend/gmail.env` (AES-256, GnuPG), encrypted
with the passphrase in `DEPLOY_SECRET_KEY`. Plaintext files are git-ignored and never committed.
See the README section "Secrets and production deployment" for encrypt, rotate, deploy and
recovery. A lost passphrase cannot be recovered.
