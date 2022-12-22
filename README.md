# `cryostat-agent`

Discovery agent plugin for [Cryostat](https://github.com/cryostatio/cryostat).

Cryostat has a Discovery API to allow service locator bridges ("Discovery Plugins") to inform Cryostat about the
presence of connectable JVM applications. These Discovery Plugins may be implemented in a way that bridges a service
locator system to Cryostat's API, or the Discovery Plugin may be implemented on individual application instances so
that the applications may self-publish themselves to the Cryostat Discovery API. This agent implements a Discovery
Plugin as an attachable JVM agent that can be included in a target application to enhance it for self-publishing its
location to Cryostat.

## REQUIREMENTS
Build Requirements:
- Git
- JDK11+
- Maven 3+

Run Requirements:
- An application JVM to attach this agent to
- Configuration for the application JVM to load this agent


An example for configuring a Quarkus application to use this agent and enable JMX:
```
JAVA_OPTIONS="-Dcom.sun.management.jmxremote.port=9091 -Dcom.sun.management.jmxremote.rmi.port=9091 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -javaagent:/deployments/app/cryostat-agent-${CRYOSTAT_AGENT_VERSION}.jar"
```
This assumes that the agent JAR has been included in the application image within `/deployments/app/`.

## CONFIGURATION

`cryostat-agent` uses [smallrye-config](https://github.com/smallrye/smallrye-config) for configuration.
Below is a list of configuration properties that can be used to influence how `cryostat-agent` runs
and how it advertises itself to a Cryostat server instance. Required properties are indicated with a checked box.

- [x] `cryostat.agent.baseuri` [`java.net.URI`]: the URL location of the Cryostat server backend that this agent advertises itself to.
- [x] `cryostat.agent.callback` [`java.net.URI`]: a URL pointing back to this agent, ex. `"https://12.34.56.78:1234/"`. Cryostat will use this URL to perform health checks and request updates from the agent. This reflects the externally-visible IP address/hostname and port where this application and agent can be found.
- [ ] `cryostat.agent.hostname` [`String`]: the hostname for this application instance. This will be used for the published JMX connection URL. If not provided then the default is to attempt to resolve the localhost hostname.
- [ ] `cryostat.agent.realm` [`String`]: the Cryostat Discovery API "realm" that this agent belongs to. This should be unique per agent instance. The default includes the `cryostat.agent.app.name` and a random UUID.
- [ ] `cryostat.agent.authorization` [`String`]: Authorization header value to include with API requests to the Cryostat server, ex. `Bearer abcd1234`. Default `None`.
- [ ] `cryostat.agent.ssl.trust-all` [`boolean`]: Control whether the agent trusts all certificates presented by the Cryostat server. Default `true`.
- [ ] `cryostat.agent.ssl.verify-hostname` [`boolean`]: Control whether the agent verifies hostnames on certificates presented by the Cryostat server. Default `false`.
- [ ] `cryostat.agent.webserver.host` [`String`]: the internal hostname or IP address for the embedded webserver to bind to. Default `0.0.0.0`.
- [ ] `cryostat.agent.webserver.port` [`int`]: the internal port number for the embedded webserver to bind to. Default `9977`.
- [ ] `cryostat.agent.app.name` [`String`]: a human-friendly name for this application. Default `cryostat-agent`.
- [ ] `cryostat.agent.app.jmx.port` [`int`]: the JMX RMI port that the application is listening on. The default is to attempt to determine this from the `com.sun.management.jmxremote.port` system property.
- [ ] `cryostat.agent.registration.retry-ms` [`int`]: the duration in milliseconds between attempts to register with the Cryostat server. Default `5000`.
- [ ] `cryostat.agent.harvester.period-ms` [`int`]: the length of time between JFR collections and pushes by the harvester. This also controls the maximum age of data stored in the buffer for the harvester's managed Flight Recording. Every `period-ms` the harvester will upload a JFR binary file to the `cryostat.agent.baseuri` archives. Default `-1`, which indicates no harvesting will be performed.
- [ ] `cryostat.agent.harvester.template` [`String`]: the name of the `.jfc` event template configuration to use for the harvester's managed Flight Recording. Default `default`, the continous monitoring event template.

These properties can be set by JVM system properties or by environment variables. For example, the property
`cryostat.agent.baseuri` can be set using `-Dcryostat.agent.baseuri=https://mycryostat.example.com:1234/` or
`CRYOSTAT_AGENT_BASEURI=https://mycryostat.example.com:1234/`. See
[here](https://smallrye.io/smallrye-config/2.11.1/config/environment-variables/) for more detail.
