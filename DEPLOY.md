> **Automated path:** after the secrets are encrypted into `config/secrets.tar.gpg`, deploys are one command, `DEPLOY_SECRET_KEY=... ./deploy.sh`; it decrypts to `.tmp-secrets/`, runs the compose command below with `--env-file .tmp-secrets/deploy.env`, and deletes the plaintext. The manual `--env-file deploy/.env` commands below still work.

# Hosting on Oracle Cloud (free)

One Oracle Cloud **Always Free** Ampere VM runs everything: Postgres, Redis, the backend, the
frontend, HTTPS (Caddy), and optionally a local Ollama model. The address is a free DuckDNS name.
Expect about an hour the first time.

```
browser ──HTTPS──▶ Caddy :443 ──▶ frontend (nginx) ──/api──▶ backend ──▶ Postgres, Redis, (Ollama)
                                                             └──────▶ Gemini (email reading), Gmail API
```

Only ports 80/443 are open to the internet. The database is never reachable from outside.

Free-tier terms change — check Oracle's current "Always Free" page when you sign up.

---

## What you need before starting

| Account | Why | Cost |
|---|---|---|
| Oracle Cloud | The server | Free (card needed for identity verification) |
| DuckDNS (sign in with Google/GitHub) | `yourname.duckdns.org` address | Free |
| Google Cloud Console (you already have the Gmail OAuth client) | Gmail sign-in | Free |
| Gemini API key, from a **billing-enabled** project | Reads email | Pay-per-use, small; free-tier prompts may be used by Google to improve its products |
| SMTP for sign-up codes — e.g. Brevo free tier, or Gmail with an app password | Sign-up verification emails | Free |

---

## 1. Create the Oracle account

1. Sign up at oracle.com/cloud/free. Pick your **home region** carefully (e.g. India South –
   Hyderabad, or India West – Mumbai). It can't be changed, and Always Free machines only run in
   the home region.
2. **Recommended: upgrade to "Pay As You Go"** (Billing → Upgrade). You still pay nothing while you
   stay within Always Free limits, but:
   - Oracle can reclaim Always Free machines on non-upgraded accounts that sit mostly idle for a
     week. A personal app is idle most of the time.
   - Ampere machines are far easier to get ("out of capacity" errors are common on free accounts).

   Then set a budget alert (Billing → Budgets, e.g. ₹100) so any accidental paid resource emails you.

## 2. Create the server

Compute → Instances → **Create instance**:

- **Image:** Canonical Ubuntu 24.04 (the aarch64 build is picked automatically for Ampere).
- **Shape:** Ampere → `VM.Standard.A1.Flex`, **4 OCPUs, 24 GB memory** (the whole free allowance).
- **Networking:** create a new VCN with a public subnet; **assign a public IPv4 address**.
- **SSH keys:** upload your public key (`~/.ssh/id_ed25519.pub`; create one with `ssh-keygen -t ed25519` if you don't have one).
- **Boot volume:** 100 GB (up to 200 GB total is free).

"Out of capacity"? Try another availability domain, or retry later.

Note the **public IP address** once it's running.

## 3. Open ports 80 and 443

**In Oracle:** Networking → Virtual cloud networks → your VCN → Security Lists → Default →
**Add ingress rules**: source `0.0.0.0/0`, TCP, destination port `80`; and the same for `443`.

**On the server:** Oracle's Ubuntu images also block these in the machine's own firewall. SSH in
(`ssh ubuntu@<public-ip>`) and run:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 4. Get the address

On duckdns.org: create a subdomain (e.g. `sharmafamily`) and set its IP to the server's public IP.
Copy your DuckDNS **token** from the top of the page.

## 5. Install Docker and get the code onto the server

```bash
sudo apt-get update && sudo apt-get -y upgrade
sudo apt-get install -y unattended-upgrades git
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && exit     # log out so the group change applies
```

SSH back in, then clone the repository (a private GitHub repo works with a
[deploy key](https://docs.github.com/en/authentication/connecting-to-github-with-ssh/managing-deploy-keys)):

```bash
git clone git@github.com:<you>/indian-markets-ai-platform.git
cd indian-markets-ai-platform
```

## 6. Configure

```bash
./deploy/init-secrets.sh      # creates deploy/.env (once; then encrypt it, see README "Secrets and production deployment") and generates DB/Redis/JWT/encryption secrets
nano deploy/.env              # fill in the rest (see below)
```

Fill in:

- `DOMAIN` — e.g. `sharmafamily.duckdns.org`; `DUCKDNS_TOKEN`
- `SIGNUP_ALLOWED_EMAILS` — you, your parents, everyone allowed to register, comma-separated
- `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`
- `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`
- `GEMINI_API_KEY`

**Copy `JWT_SECRET` and `PDF_PASSWORD_ENC_KEY` into your password manager.** Losing them signs
everyone out and makes saved statement passwords unreadable.

## 7. Google Cloud Console (Gmail sign-in)

APIs & Services → Credentials → your OAuth client:

- **Authorized redirect URIs:** add `https://<DOMAIN>/api/gmail/callback`
- **Authorized JavaScript origins:** add `https://<DOMAIN>`

OAuth consent screen — choose one:

- **Testing:** add every family member's Gmail under *Test users*. Google disconnects Gmail every
  7 days, so each person reconnects weekly.
- **In production (unverified):** click *Publish app*. Each person sees a one-time "Google hasn't
  verified this app" screen (Advanced → continue), then the connection lasts. Allows up to 100 users.

## 8. Start it

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.prod.yml logs -f backend     # Ctrl-C to stop watching
```

The first build takes 10–20 minutes. The backend then needs about 2 minutes to start — the site
shows a 502 until it's ready. Open `https://<DOMAIN>`; Caddy gets the certificate on first visit.

**Optional — local AI for the non-email features** (keeps them free and on your server):

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env --profile ollama up -d
docker compose -f deploy/docker-compose.prod.yml exec ollama ollama pull qwen2.5:7b
```

Then set `LLM_PROVIDER=ollama` in `deploy/.env` and run the `up -d` command from step 8 again.

## 9. Backups and address updates

```bash
crontab -e
```

Add:

```
30 2 * * * /home/ubuntu/indian-markets-ai-platform/deploy/backup.sh >> /home/ubuntu/backup.log 2>&1
*/5 * * * * /home/ubuntu/indian-markets-ai-platform/deploy/duckdns-update.sh >/dev/null 2>&1
```

Backups are kept on the server for 14 days in `deploy/backups/`. **Also keep a copy off the
server** — if the VM is lost, so are they. Simplest, from your laptop now and then:

```bash
scp 'ubuntu@<public-ip>:indian-markets-ai-platform/deploy/backups/*.sql.gz' ~/marketai-backups/
```

**Restore** a backup (overwrites the current data):

```bash
gunzip -c deploy/backups/marketai-YYYYMMDD-HHMM.sql.gz | \
  docker compose -f deploy/docker-compose.prod.yml exec -T postgres psql -U marketai -d marketai_db
```

## 10. First use

1. Open `https://<DOMAIN>` → Register with an email on the allowlist → enter the emailed code.
2. Settings → add PAN / date of birth (for statement passwords) → connect Gmail → run the sync.
3. Family members do the same with their own accounts. Each account's data is separate.

To invite someone new: add their email to `SIGNUP_ALLOWED_EMAILS` and run the step 8 `up -d` command again.

## Updating to a new version

```bash
cd ~/indian-markets-ai-platform
./deploy/backup.sh                         # always, before an update
git pull
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
```

## Troubleshooting

| Symptom | Check |
|---|---|
| Site doesn't load at all | DuckDNS IP matches the server; ports 80/443 open in **both** the Security List and `iptables` (step 3) |
| 502 Bad Gateway | Backend still starting, or crashed: `docker compose -f deploy/docker-compose.prod.yml logs backend` |
| "Sign-up is by invitation only" | Email missing from `SIGNUP_ALLOWED_EMAILS`, or containers not restarted after editing it |
| Verification code never arrives | `MAIL_*` settings; the backend log shows the SMTP error |
| Gmail connect fails with `redirect_uri_mismatch` | Redirect URI in Google Console must be exactly `https://<DOMAIN>/api/gmail/callback` |
| Emails stay in Needs Review as "extractor unavailable" | `GEMINI_API_KEY` missing or out of quota |
