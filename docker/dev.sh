#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: sh docker/dev.sh lean" >&2
    exit 2
fi

case "$1" in
    lean) ;;
    *)
        echo "Unknown job: $1" >&2
        exit 2
        ;;
esac

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if ! docker info >/dev/null 2>&1; then
    echo "Docker engine is unavailable. Start Docker and try again." >&2
    exit 1
fi

hash=$(sha256sum "$root/docker/build/Dockerfile")
image="reflect-build:$(printf '%s' "$hash" | cut -c1-12)"
if ! docker image inspect "$image" >/dev/null 2>&1; then
    if [ -n "${REFLECT_EXTRA_CA:-}" ]; then
        if [ ! -f "$REFLECT_EXTRA_CA" ]; then
            echo "REFLECT_EXTRA_CA is not a file: $REFLECT_EXTRA_CA" >&2
            exit 2
        fi
        docker build --platform linux/amd64 --tag "$image" \
            --secret "id=reflect_extra_ca,src=$REFLECT_EXTRA_CA" "$root/docker/build"
    else
        docker build --platform linux/amd64 --tag "$image" "$root/docker/build"
    fi
fi

exec docker run --rm --platform linux/amd64 --network none \
    --user "$(id -u):$(id -g)" --env HOME=/tmp \
    --mount "type=bind,source=$root,target=/work" --workdir /work \
    "$image" sh docker/ci.sh "$1"
