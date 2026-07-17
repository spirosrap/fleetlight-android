#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

patterns=(
  '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
  'AKIA[0-9A-Z]{16}'
  'gh[pousr]_[A-Za-z0-9_]{20,}'
  'sk-[A-Za-z0-9_-]{20,}'
  '(^|[^0-9])100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]{1,3}\.[0-9]{1,3}([^0-9]|$)'
  '(^|[^0-9])10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}([^0-9]|$)'
  '(^|[^0-9])192\.168\.[0-9]{1,3}\.[0-9]{1,3}([^0-9]|$)'
  '/Users/[A-Za-z0-9._-]+'
  '/home/[A-Za-z0-9._-]+'
  '\.ts\.net'
)

failed=0
for pattern in "${patterns[@]}"; do
  matches=$(rg -l --hidden --glob '!.git/**' --glob '!**/build/**' --glob '!scripts/privacy-check.sh' -e "$pattern" . || true)
  if [[ -n "$matches" ]]; then
    echo "Privacy check failed; prohibited content matched in:"
    echo "$matches"
    failed=1
  fi
done

if [[ -n "${FLEETLIGHT_PRIVACY_DENYLIST:-}" ]]; then
  while IFS= read -r term; do
    [[ -z "$term" ]] && continue
    matches=$(rg -l -F -i --hidden --glob '!.git/**' --glob '!**/build/**' --glob '!scripts/privacy-check.sh' -- "$term" . || true)
    if [[ -n "$matches" ]]; then
      echo "Privacy check failed; an external denylist term matched in:"
      echo "$matches"
      failed=1
    fi
  done <<< "$FLEETLIGHT_PRIVACY_DENYLIST"
fi

for forbidden in keystore.properties '*.jks' '*.keystore' '*.apk' '*.aab'; do
  if git rev-parse --is-inside-work-tree >/dev/null 2>&1 && git ls-files --error-unmatch "$forbidden" >/dev/null 2>&1; then
    echo "Privacy check failed; forbidden runtime/signing artifact is tracked: $forbidden"
    failed=1
  fi
done

if [[ $failed -ne 0 ]]; then
  exit 1
fi

echo "Privacy check passed."
