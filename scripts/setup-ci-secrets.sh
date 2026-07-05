#!/usr/bin/env bash
#
# Sets every GitHub Actions secret that .github/workflows/release.yml needs,
# reading from your local (git-ignored) files. Run once.
#
# Requires:
#   - gh CLI, authenticated:  gh auth login
#   - Run from the repo root:  bash scripts/setup-ci-secrets.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

echo "Setting binary secrets (base64)…"
base64 miko-release.jks        | tr -d '\n' | gh secret set KEYSTORE_BASE64
base64 app/google-services.json | tr -d '\n' | gh secret set GOOGLE_SERVICES_JSON

echo "Setting keystore secrets…"
val() { grep "^$1=" "$2" | cut -d= -f2- | sed 's/\\//g'; }   # also strips java-properties backslash escapes
gh secret set KEYSTORE_PASSWORD --body "$(val storePassword keystore.properties)"
gh secret set KEY_PASSWORD      --body "$(val keyPassword   keystore.properties)"
gh secret set KEY_ALIAS         --body "$(val keyAlias      keystore.properties)"

echo "Setting API-key secrets from local.properties…"
for k in GEMINI_API_KEYS DEEPGRAM_API_KEY COGNEE_API_KEY COGNEE_BASE_URL \
         COGNEE_TENANT_ID COGNEE_USER_ID TAVILY_API GCLOUD_PROXY_URL GCLOUD_PROXY_URL_KEY; do
  gh secret set "$k" --body "$(val "$k" local.properties || true)"
done

echo "All secrets set. Push a tag (e.g. 'git tag v1.0.0 && git push origin v1.0.0') to build a release."
