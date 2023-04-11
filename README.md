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
- [ ] `cryostat.agent.webclient.ssl.trust-all` [`boolean`]: Control whether the agent trusts all certificates presented by the Cryostat server. Default `false`. This should only be overridden for development and testing purposes, never in production.
- [ ] `cryostat.agent.webclient.ssl.verify-hostname` [`boolean`]: Control whether the agent verifies hostnames on certificates presented by the Cryostat server. Default `true`. This should only be overridden for development and testing purposes, never in production.
- [ ] `cryostat.agent.webclient.connect.timeout-ms` [`long`]: the duration in milliseconds to wait for HTTP requests to the Cryostat server to connect. Default `1000`.
- [ ] `cryostat.agent.webclient.response.timeout-ms` [`long`]: the duration in milliseconds to wait for HTTP requests to the Cryostat server to respond. Default `1000`.
- [ ] `cryostat.agent.webserver.host` [`String`]: the internal hostname or IP address for the embedded webserver to bind to. Default `0.0.0.0`.
- [ ] `cryostat.agent.webserver.port` [`int`]: the internal port number for the embedded webserver to bind to. Default `9977`.
- [ ] `cryostat.agent.app.name` [`String`]: a human-friendly name for this application. Default `cryostat-agent`.
- [ ] `cryostat.agent.app.jmx.port` [`int`]: the JMX RMI port that the application is listening on. The default is to attempt to determine this from the `com.sun.management.jmxremote.port` system property.
- [ ] `cryostat.agent.registration.retry-ms` [`long`]: the duration in milliseconds between attempts to register with the Cryostat server. Default `5000`.
- [ ] `cryostat.agent.exit.signals` [`[String]`]: a comma-separated list of signals that the agent should handle. When any of these signals is caught the agent initiates an orderly shutdown, deregistering from the Cryostat server and potentially uploading the latest harvested JFR data. Default `INT,TERM`.
- [ ] `cryostat.agent.exit.deregistration.timeout-ms` [`long`]: the duration in milliseconds to wait for a response from the Cryostat server when attempting to deregister at shutdown time . Default `3s`.
- [ ] `cryostat.agent.harvester.period-ms` [`long`]: the length of time between JFR collections and pushes by the harvester. This also controls the maximum age of data stored in the buffer for the harvester's managed Flight Recording. Every `period-ms` the harvester will upload a JFR binary file to the `cryostat.agent.baseuri` archives. Default `-1`, which indicates no harvesting will be performed.
- [ ] `cryostat.agent.harvester.template` [`String`]: the name of the `.jfc` event template configuration to use for the harvester's managed Flight Recording. Default `default`, the continuous monitoring event template.
- [ ] `cryostat.agent.harvester.max-files` [`String`]: the maximum number of pushed files that Cryostat will keep over the network from the agent. This is supplied to the harvester's push requests which instructs Cryostat to prune, in a FIFO manner, the oldest JFR files within the attached JVM target's storage, while the number of stored recordings is greater than this configuration's maximum file limit. Default `2147483647` (`Integer.MAX_VALUE`).
- [ ] `cryostat.agent.harvester.upload.timeout-ms` [`long`]: the duration in milliseconds to wait for HTTP upload requests to the Cryostat server to complete and respond. Default `30000`.
- [ ] `cryostat.agent.harvester.exit.max-age-ms` [`long`]: the JFR `maxage` setting, specified in milliseconds, to apply to recording data uploaded to the Cryostat server when the JVM this Agent instance is attached to exits. This ensures that tail-end data is captured between the last periodic push and the application exit. Exit uploads only occur when the application receives `SIGINT`/`SIGTERM` from the operating system or container platform.
- [ ] `cryostat.agent.harvester.exit.max-size-b` [`long`]: the JFR `maxsize` setting, specified in bytes, to apply to exit uploads as described above.
- [ ] `cryostat.agent.harvester.max-age-ms` [`long`]: the JFR `maxage` setting, specified in milliseconds, to apply to periodic uploads during the application lifecycle. Defaults to `0`, which is interpreted as 1.5x the harvester period (`cryostat.agent.harvester.-period-ms`).
- [ ] `cryostat.agent.harvester.max-size-b` [`long`]: the JFR `maxsize` setting, specified in bytes, to apply to periodic uploads during the application lifecycle. Defaults to `0`, which means `unlimited`.

These properties can be set by JVM system properties or by environment variables. For example, the property
`cryostat.agent.baseuri` can be set using `-Dcryostat.agent.baseuri=https://mycryostat.example.com:1234/` or
`CRYOSTAT_AGENT_BASEURI=https://mycryostat.example.com:1234/`. See
[here](https://smallrye.io/smallrye-config/2.11.1/config/environment-variables/) for more detail.
