FROM registry.access.redhat.com/ubi9-micro:9.8-1782840931@sha256:35de56a9413112f1474e392ebc35e0cf6f0fb484c8e8877bbae59b513694b41f
ARG OUTDIR=/cryostat/agent
COPY target/cryostat-agent-*-shaded.jar ${OUTDIR}/
RUN ln -s ${OUTDIR}/cryostat-agent-*-shaded.jar ${OUTDIR}/cryostat-agent-shaded.jar
USER 1001
