#!/bin/bash
set -euo pipefail
IFS=$'\n\t'

echo "📦 Staging artifacts..."
./mvnw --batch-mode --no-transfer-progress -Ppublication -DskipTests=true -Dspotless.check.skip=true

echo "🚀 Releasing..."
./mvnw --batch-mode --no-transfer-progress -Prelease jreleaser:full-release

echo "🎉 Done!"
