<<<<<<< HEAD
FROM registry.access.redhat.com/ubi9-micro:9.7
=======
FROM registry.access.redhat.com/ubi9-micro:9.7-1771346390
>>>>>>> 3bf0a3e (chore(deps): update registry.access.redhat.com/ubi9-micro docker tag to v9.7-1771346390 (#810))
ARG OUTDIR=/cryostat/agent
COPY target/cryostat-agent-*-shaded.jar ${OUTDIR}/
RUN ln -s ${OUTDIR}/cryostat-agent-*-shaded.jar ${OUTDIR}/cryostat-agent-shaded.jar
USER 1001
