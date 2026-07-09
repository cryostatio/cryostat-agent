#!/bin/bash
set -euo pipefail
IFS=$'\n\t'

echo "📦 Staging artifacts..."
./mvnw --batch-mode --no-transfer-progress -Ppublication,snapshots -DskipTests=true -Dspotless.check.skip=true

echo "🚀 Releasing..."
./mvnw --batch-mode --no-transfer-progress -Prelease,snapshots jreleaser:deploy

echo "🎉 Done!"
