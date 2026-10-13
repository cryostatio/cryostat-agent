FROM registry.access.redhat.com/ubi9-micro:9.8-1791765224@sha256:ff30602252a3e92706585c7c34cd3c74028eaac4ab815e6b19f8b27562bdd7e3
ARG OUTDIR=/cryostat/agent
COPY target/cryostat-agent-*-shaded.jar ${OUTDIR}/
RUN ln -s ${OUTDIR}/cryostat-agent-*-shaded.jar ${OUTDIR}/cryostat-agent-shaded.jar
USER 1001
