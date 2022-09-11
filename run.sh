#!/bin/sh

# Use main Cryostat run.sh or smoketest.sh first to spin up an instance in podman.
# This script assumes that instance will be available at https://localhost:8181/
# from a bare host process.

# FIXME this callback does not work as intended, the process' exposed port needs to
# be published into the test pod.

RJMX_PORT=10203

java \
    -Dcryostat.agent.baseuri=https://localhost:8181/ \
    -Dcryostat.agent.authorization="Basic $(echo user:pass | base64)" \
    -Dcryostat.agent.callback=https://cryostat:8181/ \
    -Dcryostat.agent.ssl.trust-all=true \
    -Dcryostat.agent.ssl.verify-hostname=false \
    -Dcom.sun.management.jmxremote.port=${RJMX_PORT} \
    -Dcom.sun.management.jmxremote.rmi.port=${RJMX_PORT} \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false \
    -jar ./target/cryostat-agent-*.jar
