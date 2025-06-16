<<<<<<< HEAD
ARG BASEIMAGE=registry.access.redhat.com/ubi9-micro:9.5
FROM ${BASEIMAGE}
=======
FROM registry.access.redhat.com/ubi9-micro:9.6
>>>>>>> 37378cf (build(deps): bump ubi9-micro from 9.5 to 9.6 in /src/main/container (#663))
ARG OUTDIR=/cryostat/agent
COPY target/cryostat-agent-*-shaded.jar ${OUTDIR}/
RUN ln -s ${OUTDIR}/cryostat-agent-*-shaded.jar ${OUTDIR}/cryostat-agent-shaded.jar
USER 1001
