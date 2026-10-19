FROM debian:trixie-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl git openjdk-21-jdk-headless zstd \
    && rm -rf /var/lib/apt/lists/*

ENV REFLECT_LEAN_VERSION=4.35.0
ENV PATH="/opt/lean/bin:${PATH}" HOME=/tmp

# SHA-256 published on the leanprover/lean4 v4.35.0 GitHub release asset.
RUN --mount=type=secret,id=reflect_extra_ca \
    set -eu; \
    test "$(dpkg --print-architecture)" = amd64; \
    trap 'rm -f /tmp/lean.tar.zst /tmp/download-ca.pem' EXIT; \
    cat /etc/ssl/certs/ca-certificates.crt > /tmp/download-ca.pem; \
    if [ -f /run/secrets/reflect_extra_ca ]; then \
        printf '\n' >> /tmp/download-ca.pem; \
        cat /run/secrets/reflect_extra_ca >> /tmp/download-ca.pem; \
    fi; \
    curl --fail --location --retry 3 --cacert /tmp/download-ca.pem \
        "https://github.com/leanprover/lean4/releases/download/v${REFLECT_LEAN_VERSION}/lean-${REFLECT_LEAN_VERSION}-linux.tar.zst" \
        -o /tmp/lean.tar.zst; \
    echo '7289597a44543246c9227b8cae501767e095ce17f6d448b95fc4a8dc97268f6d  /tmp/lean.tar.zst' \
        | sha256sum --check --strict; \
    mkdir /opt/lean; \
    tar --zstd -xf /tmp/lean.tar.zst -C /opt/lean --strip-components=1; \
    lean --version

WORKDIR /work
