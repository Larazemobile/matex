#!/bin/zsh
set -euo pipefail

SERVICE="com.orbitakidx.matex.upload"
KEY_ALIAS="matex-upload"
KEY_DIR="$HOME/Library/Application Support/Orbitakidx/Signing"
KEY_FILE="$KEY_DIR/matex-upload.jks"

mkdir -p "$KEY_DIR"

if [[ -f "$KEY_FILE" ]]; then
  if ! security find-generic-password -s "$SERVICE" >/dev/null 2>&1; then
    echo "La clave existe, pero falta su contraseña en el llavero de macOS." >&2
    exit 1
  fi
  echo "La clave de subida de Matex ya está configurada."
  exit 0
fi

UPLOAD_PASSWORD="$(openssl rand -base64 36)"
TEMP_KEY="$KEY_FILE.tmp"

trap 'rm -f "$TEMP_KEY"' EXIT

keytool -genkeypair \
  -keystore "$TEMP_KEY" \
  -storetype PKCS12 \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$UPLOAD_PASSWORD" \
  -keypass "$UPLOAD_PASSWORD" \
  -dname "CN=Orbitakidx, OU=Apps, O=Larazemobile, L=Madrid, ST=Madrid, C=ES"

security add-generic-password \
  -U \
  -a "Larazemobile" \
  -s "$SERVICE" \
  -w "$UPLOAD_PASSWORD" >/dev/null

mv "$TEMP_KEY" "$KEY_FILE"
trap - EXIT

echo "Clave de subida de Matex creada y protegida en el llavero de macOS."
