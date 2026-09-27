#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

sha="${1:?Commit SHA required}"
export AWS_REGION="${2:?AWS region required}"
order_repo="${3:?Order ECR URI required}"
frontend_repo="${4:?Frontend ECR URI required}"
parameter="${5:?Configuration parameter required}"
[[ "$sha" =~ ^[a-f0-9]{40}$ ]] || exit 2
[[ "$order_repo" == *.amazonaws.com/* && "$frontend_repo" == *.amazonaws.com/* ]] || exit 2
[[ "${order_repo%%/*}" == "${frontend_repo%%/*}" ]] || exit 2
root=/opt/logistics
release="$root/releases/$sha/deploy"
test -f "$release/compose.demo.yml"
install -d -m 700 "$root"
exec 9>"$root/deploy.lock"
flock -n 9 || { echo 'Another deployment is running.'; exit 1; }

config="$root/config.json"
aws ssm get-parameter --name "$parameter" --with-decryption --query Parameter.Value --output text > "$config"
chmod 600 "$config"
DB_PASSWORD="$(jq -er '.DB_PASSWORD | select(type == "string" and length >= 20)' "$config")"
DOMAIN="$(jq -er '.DOMAIN | select(test("^[a-z0-9][a-z0-9.-]+[a-z0-9]$"))' "$config")"
TEMPORAL_DOMAIN="$(jq -er '.TEMPORAL_DOMAIN | select(test("^[a-z0-9][a-z0-9.-]+[a-z0-9]$"))' "$config")"
DEMO_USERNAME="$(jq -er '.DEMO_USERNAME | select(test("^[A-Za-z0-9_-]+$"))' "$config")"
DEMO_PASSWORD_HASH="$(jq -er '.DEMO_PASSWORD_HASH | select(startswith("$2"))' "$config")"
export DB_PASSWORD DOMAIN TEMPORAL_DOMAIN DEMO_USERNAME DEMO_PASSWORD_HASH
export TEMPORAL_UI_URL="https://$TEMPORAL_DOMAIN"
export ORDER_IMAGE="$order_repo:$sha"
export FRONTEND_IMAGE="$frontend_repo:$sha"
export COMPOSE_PROJECT_NAME=logistics-demo
previous="$(cat "$root/current" 2>/dev/null || true)"
[[ -z "$previous" || "$previous" =~ ^[a-f0-9]{40}$ ]] || exit 2

dc() { docker compose -p logistics-demo -f "$release/compose.demo.yml" "$@"; }
rollback() {
    local result=$?
    trap - ERR
    echo "Deployment failed; requested release was $sha."
    if [[ -n "$previous" && "$previous" != "$sha" && -f "$root/releases/$previous/deploy/compose.demo.yml" ]]; then
        echo "Restoring application containers from $previous. Database migrations are not reversed."
        export ORDER_IMAGE="$order_repo:$previous" FRONTEND_IMAGE="$frontend_repo:$previous"
        release="$root/releases/$previous/deploy"
        if dc --profile public up -d --no-deps --wait --wait-timeout 180 order-service frontend gateway; then
            echo 'Previous application containers restored. Inspect the failed workflow before redeploying.'
        else
            echo 'Rollback failed; use SSM to inspect docker compose logs.'
        fi
    else
        echo 'No different successful release exists for automatic rollback.'
    fi
    exit "$result"
}

aws ecr get-login-password | docker login --username AWS --password-stdin "${order_repo%%/*}"
dc config --quiet
dc pull order-service frontend
# Install infrastructure only on the first deployment. Its versions stay unchanged on application releases.
if [[ -z "$previous" ]]; then
    dc up -d --wait --wait-timeout 180 postgres temporal
else
    for service in postgres temporal; do
        container="$(dc ps -q "$service")"
        test -n "$container"
        test "$(docker inspect --format '{{.State.Health.Status}}' "$container")" = healthy
    done
fi
trap rollback ERR
dc --profile public up -d --no-deps --wait --wait-timeout 240 order-service frontend gateway
python3 "$release/smoke.py"
# Require valid public TLS and confirm both sites enforce authentication.
for domain in "$DOMAIN" "$TEMPORAL_DOMAIN"; do
    ready=false
    for _ in $(seq 1 30); do
        code="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 8 "https://$domain/" || true)"
        if [[ "$code" == 401 ]]; then ready=true; break; fi
        sleep 3
    done
    "$ready"
done
if [[ -n "$previous" && "$previous" != "$sha" ]]; then printf '%s\n' "$previous" > "$root/previous"; fi
printf '%s\n' "$sha" > "$root/current"
trap - ERR
echo "Release $sha is healthy at https://$DOMAIN"
