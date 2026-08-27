FROM registry.access.redhat.com/ubi9-micro:9.8-1787778798@sha256:f332c99eb8f798a8486821c91937f10ad64ee83d7e739303be2df051040918f6
ARG OUTDIR=/cryostat/agent
COPY target/cryostat-agent-*-shaded.jar ${OUTDIR}/
RUN ln -s ${OUTDIR}/cryostat-agent-*-shaded.jar ${OUTDIR}/cryostat-agent-shaded.jar
USER 1001
