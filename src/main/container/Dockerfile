FROM registry.access.redhat.com/ubi9-micro:9.8-1793860449@sha256:6d9eff186ce3ccf399c1835265e7fd3f83bd112cc3422da2d39fd44e234aab59
ARG OUTDIR=/cryostat/agent
COPY target/cryostat-agent-*-shaded.jar ${OUTDIR}/
RUN ln -s ${OUTDIR}/cryostat-agent-*-shaded.jar ${OUTDIR}/cryostat-agent-shaded.jar
USER 1001
