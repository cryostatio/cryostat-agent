FROM registry.access.redhat.com/ubi9-micro:9.9-1794478307@sha256:dbd325b96bcd432dd42816d31412bdf1dc0fb973b79ed310344bc23c76c068db
ARG OUTDIR=/cryostat/agent
COPY target/cryostat-agent-*-shaded.jar ${OUTDIR}/
RUN ln -s ${OUTDIR}/cryostat-agent-*-shaded.jar ${OUTDIR}/cryostat-agent-shaded.jar
USER 1001
