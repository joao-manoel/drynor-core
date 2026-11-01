#!/usr/bin/env bash
set -euo pipefail
ROOT="${DRYNOR_ROOT:-/home/neto/drynor-rsps}"
cd "$ROOT"
mkdir -p .deploy
exec 9>.deploy/deploy.lock
flock -n 9 || { echo 'Já existe um deploy em execução.' >&2; exit 1; }
CORE="$ROOT/repositories/drynor-core"
if [[ "${1:-}" == world || "${1:-}" == lobby ]]; then
  [[ $(git -C "$CORE" branch --show-current) == main ]] || { echo 'Core deve estar em main.'; exit 1; }
  [[ -z $(git -C "$CORE" status --porcelain --untracked-files=no) ]] || { echo 'Core possui alterações locais.'; exit 1; }
  git -C "$CORE" pull --ff-only
fi
exec bash "$CORE/deploy/deploy.sh" "$@"
