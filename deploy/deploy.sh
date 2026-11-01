#!/usr/bin/env bash
set -Eeuo pipefail
export DRYNOR_ROOT="${DRYNOR_ROOT:-/home/neto/drynor-rsps}"
cd "$DRYNOR_ROOT"
INFRA="$DRYNOR_ROOT/repositories/drynor-core/deploy"
mkdir -p .deploy .rollback

compose() {
  local options=(-f "$DRYNOR_ROOT/compose.yml") file
  for file in .deploy/lobby.yml .deploy/world.yml; do
    [[ ! -f "$file" ]] || options+=(-f "$DRYNOR_ROOT/$file")
  done
  docker compose --project-directory "$DRYNOR_ROOT" "${options[@]}" "$@"
}

wait_ready() {
  local i check_service="${1:-$SERVICE}"
  for i in {1..90}; do
    if [[ "$check_service" == lobby ]]; then
      if curl -fsS --max-time 2 http://127.0.0.1:4040/ready 2>/dev/null | grep -q '"ready":true'; then return 0; fi
    else
      if docker exec drynor-rsps-world-1 bash -c 'exec 3<>/dev/tcp/127.0.0.1/43596; printf "GET /api/players HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; cat <&3' 2>/dev/null | grep -q '200 OK' &&
        curl -fsS --max-time 2 http://127.0.0.1:4040/health | grep -Eq '"worlds":[1-9]'; then
        if docker logs drynor-rsps-world-1 2>&1 | grep -Eq 'Error executing startup hook|Exception in thread "main"'; then return 1; fi
        return 0
      fi
    fi
    sleep 2
  done
  compose logs --tail=80 "$check_service"
  return 1
}

restore() {
  if [[ -f "$BACKUP/overlay.yml" ]]; then
    cp "$BACKUP/overlay.yml" ".deploy/$SERVICE.yml"
  else
    rm -f -- ".deploy/$SERVICE.yml"
  fi
  if [[ -f "$BACKUP/gameplay-before.tar" ]]; then tar -xf "$BACKUP/gameplay-before.tar" -C world; fi
  if [[ -f "$BACKUP/new-files.txt" ]]; then
    local rel
    while IFS= read -r rel; do
      [[ "$rel" == data/* || "$rel" == plugins/* ]] && [[ "$rel" != *..* ]] || return 1
      rm -f -- "world/$rel"
    done < "$BACKUP/new-files.txt"
  fi
}

cleanup_tests() {
  if [[ "${TESTS_STARTED:-0}" == 1 ]]; then docker rm -fv drynor-deploy-test-mongo >/dev/null 2>&1 || true; fi
}

failed() {
  local code=$?
  trap - ERR
  echo 'Deploy interrompido; recuperando o serviço anterior.' >&2
  cleanup_tests
  if [[ "${INSTALLED:-0}" == 1 ]]; then restore; fi
  if [[ "${STOPPED:-0}" == 1 ]]; then
    compose up -d --no-deps "$SERVICE" || true
    if [[ "$SERVICE" == lobby && "${WORLD_WAS_RUNNING:-0}" == 1 ]]; then docker start drynor-rsps-world-1 >/dev/null || true; fi
  fi
  echo "Consulte o backup: ${BACKUP:-não criado}." >&2
  exit "$code"
}

if [[ "${1:-}" == status ]]; then
  compose ps
  curl -fsS http://127.0.0.1:4040/health
  exit 0
fi
if [[ "${1:-}" == check ]]; then
  for repo in drynor-core drynor-world-server drynor-lobby-server; do
    test -d "repositories/$repo/.git"
    printf '%s: ' "$repo"; git -C "repositories/$repo" rev-parse --short HEAD
  done
  test -f cache-unpacked/gamevals/obj.json
  test -f world/data/worldConfig.json
  command -v rsync >/dev/null; command -v curl >/dev/null
  docker buildx version
  compose config --quiet
  echo 'Estrutura, catálogos e Compose conferidos; nenhum serviço alterado.'
  exit 0
fi

ACTION="${1:-}"
SERVICE="${2:-}"
if [[ "$ACTION" == world || "$ACTION" == lobby ]]; then SERVICE="$ACTION"; ACTION=deploy; fi
[[ "$SERVICE" == world || "$SERVICE" == lobby ]] && [[ "$ACTION" == deploy || "$ACTION" == rollback ]] || {
  echo 'Uso: ./deploy.sh world | lobby | status | check | rollback world | rollback lobby'; exit 2;
}

if [[ "$ACTION" == rollback ]]; then
  BACKUP=$(cat ".deploy/last-$SERVICE")
  [[ "$BACKUP" == "$DRYNOR_ROOT/.rollback/"* ]] || exit 1
  test -d "$BACKUP"
  if [[ "$SERVICE" == lobby ]]; then compose stop world; fi
  compose stop "$SERVICE"
  restore
  compose up -d --no-deps "$SERVICE"
  wait_ready
  if [[ "$SERVICE" == lobby ]]; then docker start drynor-rsps-world-1 >/dev/null; fi
  echo 'Versão anterior restaurada. Confira o login no cliente.'
  exit 0
fi

REPO="$DRYNOR_ROOT/repositories/drynor-$SERVICE-server"
[[ $(git -C "$REPO" branch --show-current) == main ]] || { echo 'O repositório deve estar em main.'; exit 1; }
[[ -z $(git -C "$REPO" status --porcelain --untracked-files=no) ]] || { echo 'Há alterações locais na VPS.'; exit 1; }
git -C "$REPO" pull --ff-only
test -f cache-unpacked/gamevals/obj.json
test -f world/data/worldConfig.json
export SOURCE_COMMIT CORE_COMMIT TARGET_IMAGE
SOURCE_COMMIT=$(git -C "$REPO" rev-parse HEAD)
CORE_COMMIT=$(git -C repositories/drynor-core rev-parse HEAD)
RELEASE="$(date +%Y%m%d-%H%M%S)-$SERVICE-${SOURCE_COMMIT:0:8}"
TARGET_IMAGE="drynor-$SERVICE:$RELEASE"
BACKUP="$DRYNOR_ROOT/.rollback/$RELEASE"
mkdir -p "$BACKUP"
printf '%s\n%s\n' "$SOURCE_COMMIT" "$CORE_COMMIT" > "$BACKUP/commits.txt"
[[ ! -f ".deploy/$SERVICE.yml" ]] || cp ".deploy/$SERVICE.yml" "$BACKUP/overlay.yml"
docker inspect "drynor-rsps-$SERVICE-1" --format '{{.Image}}' > "$BACKUP/previous-image.txt"
WORLD_WAS_RUNNING=$(docker inspect drynor-rsps-world-1 --format '{{if .State.Running}}1{{else}}0{{end}}')
trap failed ERR
trap 'trap - INT TERM; false' INT TERM

# A VPS tem 8 GB de RAM compartilhados. Libera somente os serviços do jogo
# necessários ao build; os demais projetos e o Mongo de produção continuam.
STOPPED=1
if [[ "$SERVICE" == lobby ]]; then compose stop world lobby; else compose stop world; fi
docker buildx inspect drynor-build >/dev/null 2>&1 ||
  docker buildx create --name drynor-build --driver docker-container \
    --driver-opt memory=3g,memory-swap=4g,network=host,default-load=true \
    --buildkitd-flags '--allow-insecure-entitlement network.host' >/dev/null
docker run -d --name drynor-deploy-test-mongo --memory=256m --publish 127.0.0.1:27018:27017 mongo:8.0 >/dev/null
TESTS_STARTED=1
for attempt in {1..60}; do
  if docker exec drynor-deploy-test-mongo mongosh --quiet --eval 'db.adminCommand({ping:1}).ok' 2>/dev/null | grep -q 1; then break; fi
  sleep 1
done
docker exec drynor-deploy-test-mongo mongosh --quiet --eval 'db.adminCommand({ping:1}).ok' | grep -q 1
docker compose -f "$INFRA/compose.build.yml" build --builder drynor-build "$SERVICE"
cleanup_tests

if [[ "$SERVICE" == world ]]; then
  CONTAINER=$(docker create "$TARGET_IMAGE")
  docker cp "$CONTAINER:/opt/drynor/gameplay.tar" "$BACKUP/gameplay-new.tar"
  docker rm "$CONTAINER" >/dev/null
  mkdir -p "$BACKUP/payload"
  tar -xf "$BACKUP/gameplay-new.tar" -C "$BACKUP/payload"
  : > "$BACKUP/existing-files.txt"; : > "$BACKUP/new-files.txt"
  while IFS= read -r -d '' file; do
    rel="${file#"$BACKUP/payload/"}"
    [[ "$rel" == data/* || "$rel" == plugins/* ]] && [[ "$rel" != *..* ]]
    if [[ -e "world/$rel" ]]; then printf '%s\n' "$rel" >> "$BACKUP/existing-files.txt"; else printf '%s\n' "$rel" >> "$BACKUP/new-files.txt"; fi
  done < <(find "$BACKUP/payload" -type f -print0)
  tar -cf "$BACKUP/gameplay-before.tar" -C world -T "$BACKUP/existing-files.txt"
fi

INSTALLED=1
if [[ "$SERVICE" == world ]]; then
  rsync -ac "$BACKUP/payload/" world/
  test -z "$(rsync -rcn --out-format='%n' "$BACKUP/payload/" world/)"
  cat > .deploy/world.yml <<EOF
services:
  world:
    image: $TARGET_IMAGE
    entrypoint: [java, --enable-native-access=ALL-UNNAMED, --enable-final-field-mutation=ALL-UNNAMED, -Xms512m, -Xmx2g, -jar, /opt/drynor/world-server.jar]
    volumes: !override
      - ./world/data:/app/data
      - ./world/plugins:/app/plugins:ro
      - ./cache:/cache
EOF
else
  cat > .deploy/lobby.yml <<EOF
services:
  lobby:
    image: $TARGET_IMAGE
    entrypoint: [/bin/sh, /opt/drynor/lobby/bin/drynor-lobby-server]
    volumes: !override
      - ./cache:/cache
EOF
fi
compose up -d --no-deps "$SERVICE"
wait_ready
if [[ "$SERVICE" == lobby && "$WORLD_WAS_RUNNING" == 1 ]]; then
  docker start drynor-rsps-world-1 >/dev/null
  wait_ready world
fi
printf '%s\n' "$BACKUP" > ".deploy/last-$SERVICE"
trap - ERR
trap - INT TERM
echo "Deploy concluído: $SERVICE, commit $SOURCE_COMMIT"
echo "Backup: $BACKUP"
echo 'Confirme o login e o conteúdo alterado no cliente.'
