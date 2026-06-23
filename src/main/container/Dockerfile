FROM registry.access.redhat.com/ubi9-micro:9.8-1779858820@sha256:b498b3ea26111ab4b81d65139f2ebd2ef9a2abb7a4588b7fdcc54889f95e9caa
ARG OUTDIR=/cryostat/agent
COPY target/cryostat-agent-*-shaded.jar ${OUTDIR}/
RUN ln -s ${OUTDIR}/cryostat-agent-*-shaded.jar ${OUTDIR}/cryostat-agent-shaded.jar
USER 1001
