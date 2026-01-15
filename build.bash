#!/usr/bin/env bash

set -xe

DIR="$(dirname "$(readlink -f "$0")")"

pushd "${DIR}"
function cleanup() {
    popd
}
trap cleanup EXIT

BUILD_IMG="${APP_REGISTRY:-quay.io}/${APP_NAMESPACE:-cryostat}/${APP_NAME:-cryostat-agent-init}"
BUILD_TAG="${APP_VERSION:-$(mvn -f "${DIR}/pom.xml" help:evaluate -B -q -DforceStdout -Dexpression=project.version)}"

"${DIR}/mvnw" -B -U -Psnapshots clean install

podman manifest create "${BUILD_IMG}:${BUILD_TAG}"

for arch in ${ARCHS:-amd64 arm64}; do
    echo "Building for ${arch} ..."
    podman build --pull=missing --platform="linux/${arch}" -t "${BUILD_IMG}:linux-${arch}" -f "${DIR}/src/main/container/Dockerfile" "${DIR}"
    podman manifest add "${BUILD_IMG}:${BUILD_TAG}" containers-storage:"${BUILD_IMG}:linux-${arch}"
done

for tag in ${TAGS}; do
    podman tag "${BUILD_IMG}:${BUILD_TAG}" "${BUILD_IMG}:${tag}"
done

if [ "${PUSH_MANIFEST}" = "true" ]; then
    podman manifest push "${BUILD_IMG}:${BUILD_TAG}" "docker://${BUILD_IMG}:${BUILD_TAG}"
    for tag in ${TAGS}; do
        podman manifest push "${BUILD_IMG}:${tag}" "docker://${BUILD_IMG}:${tag}"
    done
fi

echo "Image: ${BUILD_IMG}:${BUILD_TAG}"
