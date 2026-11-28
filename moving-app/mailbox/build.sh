#!/bin/bash
# Builds the mailbox for Windows on amd64, reproducibly: dist/housemove-mailbox.exe, then prints its SHA-256.
#   mailbox/build.sh          build it
#   mailbox/build.sh check    build it in a temporary folder and compare it with the committed one (CI does this)
# The same Go (GOTOOLCHAIN below; `go` fetches it when it is another) on any machine makes the same bytes: -trimpath leaves
# out the folders it was built in, -buildvcs=false the commit, -buildid= the build ID, -s -w the symbol tables.
set -euo pipefail
cd "$(dirname "$0")"
export GOTOOLCHAIN=go1.24.7 CGO_ENABLED=0 GOOS=windows GOARCH=amd64
out=dist/housemove-mailbox.exe
if [ "${1:-}" = check ]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  go build -trimpath -buildvcs=false -ldflags="-s -w -buildid=" -o "$tmp/housemove-mailbox.exe" .
  want=$(sha256sum "$out" | cut -d' ' -f1)
  got=$(sha256sum "$tmp/housemove-mailbox.exe" | cut -d' ' -f1)
  grep -q "$want" README.md || { echo "README.md does not give the committed exe's SHA-256 ($want)"; exit 1; }
  [ "$want" = "$got" ] || { echo "the source builds $got, the committed exe is $want"; exit 1; }
  echo "dist/housemove-mailbox.exe is what the source builds: $got"
  exit 0
fi
go build -trimpath -buildvcs=false -ldflags="-s -w -buildid=" -o "$out" .
sha256sum "$out"
