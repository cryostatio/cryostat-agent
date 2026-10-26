#!/bin/bash
set -euo pipefail
IFS=$'\n\t'

echo "📦 Staging artifacts..."
./mvnw --batch-mode --no-transfer-progress -U -Ppublication,snapshots -DskipTests=true -Dspotless.check.skip=true

echo "🚀 Releasing..."
./mvnw --batch-mode --no-transfer-progress -U -Prelease,snapshots jreleaser:deploy

echo "🎉 Done!"
