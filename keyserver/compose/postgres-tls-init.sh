#!/bin/sh
set -eu

apk add --no-cache openssl >/dev/null

if [ ! -f /tls/ca.crt ]; then
    if [ -e /tls/ca.key ] || [ -e /tls/server.key ] || [ -e /tls/server.crt ]; then
        echo "Incomplete PostgreSQL TLS volume; restore it from backup" >&2
        exit 1
    fi
    umask 077
    openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 3650 \
        -subj "/CN=Moment PostgreSQL CA" \
        -keyout /tls/ca.key -out /tls/ca.crt
    openssl req -newkey rsa:3072 -sha256 -nodes \
        -subj "/CN=postgres" \
        -keyout /tls/server.key -out /tmp/server.csr
    printf 'subjectAltName=DNS:postgres\n' > /tmp/server.ext
    openssl x509 -req -in /tmp/server.csr \
        -CA /tls/ca.crt -CAkey /tls/ca.key -CAcreateserial \
        -out /tls/server.crt -days 3650 -sha256 -extfile /tmp/server.ext
    rm -f /tmp/server.csr /tmp/server.ext /tls/ca.srl
fi

test -s /tls/ca.key
test -s /tls/server.key
test -s /tls/server.crt
chown 999:999 /tls/server.key /tls/server.crt
chmod 600 /tls/server.key
chmod 644 /tls/server.crt /tls/ca.crt
cp /tls/ca.crt /ca/ca.crt
chmod 644 /ca/ca.crt
