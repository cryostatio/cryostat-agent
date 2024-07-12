ARG BASEIMAGE=registry.access.redhat.com/ubi8-micro:8.10
FROM ${BASEIMAGE}
ARG OUTDIR=/cryostat/agent
COPY target/cryostat-agent-*-shaded.jar ${OUTDIR}/
RUN ln -s ${OUTDIR}/cryostat-agent-*-shaded.jar ${OUTDIR}/cryostat-agent-shaded.jar
