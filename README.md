# `cryostat-agent`


[![CI build and push](https://github.com/cryostatio/cryostat-agent/actions/workflows/ci.yaml/badge.svg)](https://github.com/cryostatio/cryostat-agent/actions/workflows/ci.yaml)
[![Google Group : Cryostat Development](https://img.shields.io/badge/Google%20Group-Cryostat%20Development-blue.svg)](https://groups.google.com/g/cryostat-development)


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

## Harvester

The various `cryostat.agent.harvester.*` properties may be used to configure `cryostat-agent` to start a new Flight
Recording using a given event template on Agent initialization, and to periodically collect this recorded data and push
it to the Agent's associated Cryostat server. The Agent will also attempt to push the tail end of this recording on JVM
shutdown so that the cause of an unexpected JVM shutdown might be captured for later analysis.

## SMART TRIGGERS

`cryostat-agent` supports smart triggers that listen to the values of the MBean Counters and can start recordings based
on a set of constraints specified by the user.

The general form of a smart trigger expression is as follows:

```
[constraint1(&&/||)constraint2...constraintN]~recordingTemplateNameOrLabel
```

Either the filename or label XML tag of the `${templateName}.jfc` may be used to specify the event template to use. For example, the JDK distribution ships with a `default.jfc` file containing the top-level `<configuration label="Continuous">` element. This template may be specified in the Smart Trigger definition as any of `default.jfc`, `default`, or `Continuous`.

An example for listening to CPU Usage and starting a recording using the Profiling template when it exceeds 0.2%:

```
[ProcessCpuLoad>0.2]~profile
```

An example for watching for the Thread Count to exceed 20 for longer than 10 seconds and starting a recording using the
Continuous template:

```
[ThreadCount>20&&TargetDuration>duration("10s")]~Continuous
```

These may be passed as an argument to the Cryostat Agent, for example:

```
JAVA_OPTIONS="-javaagent:/deployments/app/cryostat-agent-${CRYOSTAT_AGENT_VERSION}.jar=[ProcessCpuLoad>0.2]~profile
```

or as a [configuration property](#configuration):

```
CRYOSTAT_AGENT_SMART_TRIGGER_DEFINITIONS="[ProcessCpuLoad>0.2&&TargetDuration>duration(\"1m\")]~default.jfc"

-Dcryostat.agent.smart-trigger.definitions="[ProcessCpuLoad>0.2&&TargetDuration>duration(\"1m\")]~default.jfc"
```

Multiple Smart Trigger definitions may be specified and separated by commas, for example:

```
[ProcessCpuLoad>0.2]~profile,[ThreadCount>30]~Continuous
```

**NOTE**: Smart Triggers are evaluated on a polling basis. The poll period is configurable (see list below). This means
that your conditions are subject to sampling biases.

### Harvester Integration

Any Flight Recordings created by Smart Trigger will also be tracked by the Harvester system. This data will be captured
in a JFR Snapshot and pushed to the server on the Harvester's usual schedule. By defining Smart Triggers and a
Harvester period without a Harvester template, you can achieve a setup where dynamically-started Flight Recordings
begin when trigger conditions are met, and their data is then periodically captured until the recording is manually
stopped or the host JVM shuts down.

## CONFIGURATION

`cryostat-agent` uses [smallrye-config](https://github.com/smallrye/smallrye-config) for configuration.
Below is a list of configuration properties that can be used to influence how `cryostat-agent` runs
and how it advertises itself to a Cryostat server instance. Required properties are indicated with a checked box.

- [x] `cryostat.agent.baseuri` [`java.net.URI`]: the URL location of the Cryostat server backend that this agent advertises itself to.
- [x] `cryostat.agent.callback` [`java.net.URI`]: a URL pointing back to this agent, ex. `"https://12.34.56.78:1234/"`. Cryostat will use this URL to perform health checks and request updates from the agent. This reflects the externally-visible IP address/hostname and port where this application and agent can be found.
- [ ] `cryostat.agent.api.writes-enabled` [`boolean`]: Control whether the agent accepts "write" or mutating operations on its HTTP API. Requests for remote operations such as dynamically starting Flight Recordings will be rejected unless this is set. Default `false`.
- [ ] `cryostat.agent.instance-id` [`String`]: a unique ID for this agent instance. This will be used to uniquely identify the agent in the Cryostat discovery database, as well as to unambiguously match its encrypted stored credentials. The default is a random UUID string. It is not recommended to override this value.
- [ ] `cryostat.agent.hostname` [`String`]: the hostname for this application instance. This will be used for the published JMX connection URL. If not provided then the default is to attempt to resolve the localhost hostname.
- [ ] `cryostat.agent.realm` [`String`]: the Cryostat Discovery API "realm" that this agent belongs to. This should be unique per agent instance. The default is the value of `cryostat.agent.app.name`.
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
- [ ] `cryostat.agent.exit.deregistration.timeout-ms` [`long`]: the duration in milliseconds to wait for a response from the Cryostat server when attempting to deregister at shutdown time . Default `3000`.
- [ ] `cryostat.agent.harvester.period-ms` [`long`]: the length of time between JFR collections and pushes by the harvester. This also controls the maximum age of data stored in the buffer for the harvester's managed Flight Recording. Every `period-ms` the harvester will upload a JFR binary file to the `cryostat.agent.baseuri` archives. Default `-1`, which indicates no scheduled harvest uploading will be performed.
- [ ] `cryostat.agent.harvester.template` [`String`]: the name of the `.jfc` event template configuration to use for the harvester's managed Flight Recording. Defaults to the empty string, so that no recording is started.
- [ ] `cryostat.agent.harvester.max-files` [`String`]: the maximum number of pushed files that Cryostat will keep over the network from the agent. This is supplied to the harvester's push requests which instructs Cryostat to prune, in a FIFO manner, the oldest JFR files within the attached JVM target's storage, while the number of stored recordings is greater than this configuration's maximum file limit. Default `2147483647` (`Integer.MAX_VALUE`).
- [ ] `cryostat.agent.harvester.upload.timeout-ms` [`long`]: the duration in milliseconds to wait for HTTP upload requests to the Cryostat server to complete and respond. Default `30000`.
- [ ] `cryostat.agent.harvester.exit.max-age-ms` [`long`]: the JFR `maxage` setting, specified in milliseconds, to apply to recording data uploaded to the Cryostat server when the JVM this Agent instance is attached to exits. This ensures that tail-end data is captured between the last periodic push and the application exit. Exit uploads only occur when the application receives `SIGINT`/`SIGTERM` from the operating system or container platform.
- [ ] `cryostat.agent.harvester.exit.max-size-b` [`long`]: the JFR `maxsize` setting, specified in bytes, to apply to exit uploads as described above.
- [ ] `cryostat.agent.harvester.max-age-ms` [`long`]: the JFR `maxage` setting, specified in milliseconds, to apply to periodic uploads during the application lifecycle. Defaults to `0`, which is interpreted as 1.5x the harvester period (`cryostat.agent.harvester.period-ms`).
- [ ] `cryostat.agent.harvester.max-size-b` [`long`]: the JFR `maxsize` setting, specified in bytes, to apply to periodic uploads during the application lifecycle. Defaults to `0`, which means `unlimited`.
- [ ] `cryostat.agent.smart-trigger.definitions` [`String[]`]: a comma-separated list of Smart Trigger definitions to load at startup. Defaults to the empty string: no Smart Triggers.
- [ ] `cryostat.agent.smart-trigger.evaluation.period-ms` [`long`]: the length of time between Smart Trigger evaluations. Default `1000`.

These properties can be set by JVM system properties or by environment variables. For example, the property
`cryostat.agent.baseuri` can be set using `-Dcryostat.agent.baseuri=https://mycryostat.example.com:1234/` or
`CRYOSTAT_AGENT_BASEURI=https://mycryostat.example.com:1234/`. See
[here](https://smallrye.io/smallrye-config/2.11.1/config/environment-variables/) for more detail.
