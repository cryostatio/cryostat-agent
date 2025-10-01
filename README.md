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

### Run Requirements
- JDK11+
- a Cryostat server instance

The Cryostat project follows [semantic versioning](https://semver.org/). Generally, each Cryostat Agent minor version
is developed to and compatible with a specific Cryostat server minor release version. Other version combinations
outside of this matrix may work but are neither tested nor supported. If you run into any issues, please check if there
is an available version upgrade and ensure both your Agent and server match this version matrix.

| Cryostat Release | Server version | Agent version |
|-----------------:|---------------:|--------------:|
| < 2.3.0          | ≤ v2.2.1       | N/A           |
| 2.3.0            | v2.3.0         | v0.2.0        |
| 2.3.1            | v2.3.1         | v0.2.3        |
| 2.4.0            | v2.4.0         | v0.3.0        |
| 3.0.0            | v3.0.0         | v0.4.0        |
| 3.0.1            | v3.0.1         | v0.4.1        |
| 4.0.0            | v4.0.0         | v0.5.0        |

### Build Requirements
- Git
- OpenJDK11+
- Maven 3+

Run Requirements:
- A OpenJDK11+ application JVM to attach this agent to
- Configuration for the application JVM to load this agent
- A [Cryostat server](https://github.com/cryostatio/cryostat) instance

## Run

An example for configuring a Quarkus application to use this agent and enable JMX:
```
JAVA_OPTIONS="-Dcom.sun.management.jmxremote.port=9091 -Dcom.sun.management.jmxremote.rmi.port=9091 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -javaagent:/deployments/app/cryostat-agent-${CRYOSTAT_AGENT_VERSION}.jar"
```
This assumes that the agent JAR has been included in the application image within `/deployments/app/` or mounted as a
volume at the same location.

The agent JAR may also be loaded and dynamically attached to an already-running JVM. In this case, the agent JAR must
again be already included in the application image mounted as a volume into the container at the same location. If this
requirement is met, then the host JVM application may be started first without the Cryostat Agent with whatever its own
standard launcher process looks like. Once that application is running, the Cryostat Agent can be launched as a separate
process and asked to dynamically attach to the host application:
```
$ java -jar /path/to/cryostat-agent.jar -Dcryostat.agent.baseuri=http://cryostat.local
```

In this dynamic attachment mode, the agent [configuration](#configuration) options can be specified using the
`-D`/`property` flag. These must be placed *after* the `-jar /path/to/cryostat-agent.jar` in order to be passed as
arguments to the agent launcher - if they are passed *before* the `-jar` then they will be used by the `java` process
as system properties on the agent launcher itself, rather than having them passed on to the injected instances.

[Smart triggers](#smart-triggers) can be specified using `--smartTrigger`.

The optional PID is a positional argument and may be ignored or set to: `0` to request that the Agent launcher attempt
to find exactly one candidate JVM application to dynamically attach to, exiting if zero or more than one applications
are found; `*` to request that the Agent launch attempt to dynamically attach to every JVM application it finds; or a
specific PID to request that the Agent attempt to dynamically attach only to that one PID.

## Harvester

The various `cryostat.agent.harvester.*` properties may be used to configure `cryostat-agent` to start a new Flight
Recording using a given event template on Agent initialization, and to periodically collect this recorded data and push
it to the Agent's associated Cryostat server. The Agent will also attempt to push the tail end of this recording on JVM
shutdown so that the cause of an unexpected JVM shutdown might be captured for later analysis.

## Smart Triggers

`cryostat-agent` supports Smart Triggers that listen to the values of the MBean Counters and can start recordings based
on a set of constraints specified by the user.

The general form of a Smart Trigger expression is as follows:

```
[constraint1(&&/||)constraint2...constraintN;durationConstraint]~recordingTemplateNameOrLabel
```

Either the filename or label XML tag of the `${templateName}.jfc` may be used to specify the event template to use. For
example, the JDK distribution ships with a `default.jfc` file containing the top-level
`<configuration label="Continuous">` element. This template may be specified in the Smart Trigger definition as any of
`default.jfc`, `default`, or `Continuous`.

An example for listening to CPU Usage and starting a recording using the Profiling template when it exceeds 0.2%:

```
[ProcessCpuLoad>0.2]~profile
```

An example for watching for the Thread Count to exceed 20 for longer than 10 seconds and starting a recording using the
Continuous template:

```
[ThreadCount>20;TargetDuration>duration("10s")]~Continuous
```

The first part of the condition before the semicolon is a [Common Expression Language](https://github.com/google/cel-spec)
expression for testing
[various MBean metrics](https://github.com/cryostatio/cryostat-agent/blob/main/src/main/java/io/cryostat/agent/model/MBeanInfo.java)
. The second part after the semicolon references a special variable, `TargetDuration`, which tracks the length of time
that the first part of the condition has tested `true` for. This is converted to a `java.time.Duration` object and
compared to `duration("10s")`, a special construct that is also converted into a `java.time.Duration` object
representing the time threshold before this trigger activates. The `duration()` construct requires a `String` argument,
which may be enclosed in single `'` or double `"` quotation marks.

Smart Triggers may define more complex conditions that test multiple metrics:

```
[(ProcessCpuLoad>0.5||SystemCpuLoad>0.25)&&HeapMemoryUsagePercent>0.1;TargetDuration>duration('1m')]~Continuous
```

These may be passed as an argument to the Cryostat Agent, for example:

```
JAVA_OPTIONS="-javaagent:/deployments/app/cryostat-agent-${CRYOSTAT_AGENT_VERSION}.jar=-Dcryostat.agent.baseuri=http://cryostat.local![ProcessCpuLoad>0.2]~profile
```

(note the '!' separator between system properties overrides and Smart Triggers)

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

## Configuration

### Logging

`cryostat-agent` uses [SLF4J](https://www.slf4j.org/) for its logging. Currently it ships with `slf4j-simple` as the
log implementation, but this should change in the future to hook in to any existing SLF4J providers found on the
classpath at runtime, or fallback to `slf4j-simple` if nothing is found.

The Agent's log levels can be controlled by setting a JVM system property on the target JVM:
`-Dio.cryostat.agent.shaded.org.slf4j.simpleLogger.defaultLogLevel=trace`, or replace `trace` with whatever level you
prefer. This can also be passed as a dynamic attachment argument when starting the Agent after the target JVM.

### Agent Properties

`cryostat-agent` uses [smallrye-config](https://github.com/smallrye/smallrye-config) for configuration.
Below is a list of configuration properties that can be used to influence how `cryostat-agent` runs
and how it advertises itself to a Cryostat server instance. Properties that require configuration are indicated with a checked box.

- [x] `cryostat.agent.baseuri` [`java.net.URI`]: the URL location of the Cryostat server backend that this agent advertises itself to.
- [ ] `cryostat.agent.baseuri-range` [`String`]: a `String` representing the `io.cryostat.agent.ConfigModule.UriRange` enum level that restricts the acceptable hosts specified in the `cryostat.agent.baseuri` property. This is used to control the server locations that this Cryostat Agent instance is willing to register itself with. Default `dns_local`, which means any IP or hostname that is or resolves to `localhost`, a link-local IP address, an IP address from a private range block, or a hostname ending in `.local` will be accepted. If a `cryostat.agent.baseuri` is specified with a host outside of this range then the Agent will refuse to start. Acceptable values are: `loopback`, `link_local`, `site_local`, `dns_local`, and `public`. Each higher/more relaxed level implies that each lower level is also acceptable.
- [x] `cryostat.agent.callback` [`java.net.URI`]: a URL pointing back to this agent, ex. `"https://12.34.56.78:1234/"`. Cryostat will use this URL to perform health checks and request updates from the agent. This reflects the externally-visible IP address/hostname and port where this application and agent can be found.
- [ ] `cryostat.agent.api.writes-enabled` [`boolean`]: control whether the agent accepts "write" or mutating operations on its HTTP API. Requests for remote operations such as dynamically starting Flight Recordings will be rejected unless this is set. Default `false`.
- [ ] `cryostat.agent.instance-id` [`String`]: a unique ID for this agent instance. This will be used to uniquely identify the agent in the Cryostat discovery database, as well as to unambiguously match its encrypted stored credentials. The default is a random UUID string. It is not recommended to override this value.
- [ ] `cryostat.agent.hostname` [`String`]: the hostname for this application instance. This will be used for the published JMX connection URL. If not provided then the default is to attempt to resolve the localhost hostname.
- [ ] `cryostat.agent.realm` [`String`]: the Cryostat Discovery API "realm" that this agent belongs to. This should be unique per agent instance. The default is the value of `cryostat.agent.app.name`.
- [ ] `cryostat.agent.authorization` [`String`]: `authorization` header value to include with API requests to the Cryostat server, ex. `Bearer abcd1234`. Takes precedence over `cryostat.agent.authorization.type` and `cryostat.agent.authorization.value`. Defaults to the empty string, so `cryostat.agent.authorization.type` and `cryostat.agent.authorization.value` are used instead.
- [ ] `cryostat.agent.authorization.type` [`String`]: may be `basic`, `bearer`, `kubernetes`, `none`, or `auto`. Each performs a mapping of the `cryostat.agent.authorization.value` to produce an `Authorization` header (see above). `basic` encodes the value using base64 to produce a `Basic base64(value)` header, `bearer` directly embeds the value into a `Bearer value` header, `kubernetes` reads the value as a file location to produce a `Bearer fileAsString(value)` header, `none` produces no header. Default `auto`, which tries to do `kubernetes` first and falls back on `none`.
- [ ] `cryostat.agent.authorization.value` [`String`]: the value to map into an `Authorization` header. If the `cryostat.agent.authorization.type` is `basic` then this should be the unencoded basic credentials, ex. `user:pass`. If `bearer` then it should be the token to be presented. If `kubernetes` it should be the filesystem path to the service account token secret file. If `none` it is ignored. Default `/var/run/secrets/kubernetes.io/serviceaccount/token`, the standard location for Kubernetes serviceaccount token secret files.
- [ ] `cryostat.agent.webclient.tls.version` [`String`]: the version of TLS used for the Agent's client SSL context. Default `TLSv1.2`.
- [ ] `cryostat.agent.webclient.tls.trust-all` [`boolean`]: control whether the agent trusts all certificates presented by the Cryostat server. Default `false`. This should only be overridden for development and testing purposes, never in production.
- [ ] `cryostat.agent.webclient.tls.verify-hostname` [`boolean`]: control whether the agent verifies hostnames on certificates presented by the Cryostat server. Default `true`. This should only be overridden for development and testing purposes, never in production.
- [ ] `cryostat.agent.webclient.tls.required` [`boolean`]: Specify whether the agent should require TLS on Cryostat server connections, expecting the base URI to be an https connection with a certificate it trusts. Defaults to `true`. Should only be disabled for testing/prototyping purposes.
- [ ] `cryostat.agent.webclient.tls.trustore.cert` [`list`]: the list of truststoreConfig objects with alias, path, and type properties for certificates to be stored in the agent's truststore. For example, 'cryostat.agent.webclient.tls.truststore.cert[0].type' would be the type of the first certificate in this list. A truststoreConfig object must contain all three properties to be a valid certificate entry.
- [ ] `cryostat.agent.webclient.tls.truststore.type` [`String`]: the type of truststore used for the agent's client truststore. Default `JKS`.
- [ ] `cryostat.agent.webclient.tls.truststore.path` [`String`]: the filepath to the agent's webclient truststore. This takes precedence over `cryostat.agent.webclient.tls.truststore.cert` and must be configured with the truststore's pass with `cryostat.agent.webclient.tls.truststore.pass.file` or `cryostat.agent.webclient.tls.truststore.pass`.
- [ ] `cryostat.agent.webclient.tls.truststore.pass.file` [`String`]: the filepath to the agent's client truststore's password
- [ ] `cryostat.agent.webclient.tls.truststore.pass.charset` [`String`]: the character set used by the agent's client truststore's password. Default `utf-8`.
- [ ] `cryostat.agent.webclient.tls.truststore.pass` [`String`]: the String format of the agent's client truststore's pass
- [ ] `cryostat.agent.webclient.tls.client-auth.cert.path` [`String`]: the filepath to the TLS client authentication certificate which the agent will send to the server.
- [ ] `cryostat.agent.webclient.tls.client-auth.cert.type` [`String`]: the certificate type for TLS client authentication. Default `X.509`.
- [ ] `cryostat.agent.webclient.tls.client-auth.cert.alias` [`String`]: the certificate alias for TLS client authentication. Default `identity`.
- [ ] `cryostat.agent.webclient.tls.client-auth.key.path` [`String`]: the filepath to the TLS client authentication key which the agent will use when communicating with the server.
- [ ] `cryostat.agent.webclient.tls.client-auth.key.charset` [`String`]: the string encoding of the TLS client authentication key file. Default `utf-8`.
- [ ] `cryostat.agent.webclient.tls.client-auth.key.encoding` [`String`]: the certificate encoding of the TLS client authentication key file. Default `PKCS1`.
- [ ] `cryostat.agent.webclient.tls.client-auth.key.type` [`String`]: the key type of the TLS client authentication key. Default `RSA`.
- [ ] `cryostat.agent.webclient.tls.client-auth.key.pass.file` [`String`]: the path to a file containing the password to unlock the TLS client authentication key.
- [ ] `cryostat.agent.webclient.tls.client-auth.key.pass-charset` [`String`]: the string encoding of the file containing the TLS client authentication key password. Default `utf-8`.
- [ ] `cryostat.agent.webclient.tls.client-auth.key.pass` [`String`]: the TLS client authentication key password value. Providing the password as a mounted file is preferred.
- [ ] `cryostat.agent.webclient.tls.client-auth.keystore.pass.file` [`String`]: the path to a file containing a password to lock the TLS client authentication keystore.
- [ ] `cryostat.agent.webclient.tls.client-auth.keystore.pass-charset` [`String`]: the string encoding of the file containing the TLS client authentication keystore password.
- [ ] `cryostat.agent.webclient.tls.client-auth.keystore.pass` [`String`]: the TLS client authentication keystore password value. If no password or password file path is specified then the generated keystore will be left unlocked.
- [ ] `cryostat.agent.webclient.tls.client-auth.keystore.type` [`String`]: the TLS client authentication keystore type. Default `PKCS12`.
- [ ] `cryostat.agent.webclient.tls.client-auth.key-manager.type` [`String`]: the TLS client authentication key manager type. Default `SunX509`.
- [ ] `cryostat.agent.webclient.connect.timeout-ms` [`long`]: the duration in milliseconds to wait for HTTP requests to the Cryostat server to connect. Default `1000`.
- [ ] `cryostat.agent.webclient.response.timeout-ms` [`long`]: the duration in milliseconds to wait for HTTP requests to the Cryostat server to respond. Default `1000`.
- [ ] `cryostat.agent.webserver.host` [`String`]: the internal hostname or IP address for the embedded webserver to bind to. Default `0.0.0.0`.
- [ ] `cryostat.agent.webserver.port` [`int`]: the internal port number for the embedded webserver to bind to. Default `9977`.
- [ ] `cryostat.agent.webserver.tls.version` [`String`]: the version of TLS used for the Agent's server SSL context. Default `TLSv1.2`.
- [ ] `cryostat.agent.webserver.tls.keystore.pass` [`String`]: the filepath to the HTTPS server keystore's password
- [ ] `cryostat.agent.webserver.tls.keystore.pass.charset` [`String`]: the character set used by the HTTPS server keystore's password. Default `utf-8`.
- [ ] `cryostat.agent.webserver.tls.keystore.file` [`String`]: the filepath to the HTTPS server keystore
- [ ] `cryostat.agent.webserver.tls.keystore.type` [`String`]: the type of keystore used for the Agent's HTTPS server. Default `PKCS12`.
- [ ] `cryostat.agent.webserver.tls.key.alias` [`String`]: the alias used for the keystore entry to contain this key for the HTTPS server.
- [ ] `cryostat.agent.webserver.tls.key.path` [`String`]: the filepath to the TLS key used by the HTTPS server.
- [ ] `cryostat.agent.webserver.tls.key.charset` [`String`]: the string encoding of the TLS key file. Default `utf-8`.
- [ ] `cryostat.agent.webserver.tls.key.encoding` [`String`]: the certificate encoding of the TLS key file. Default `PKCS1`.
- [ ] `cryostat.agent.webserver.tls.key.type` [`String`]: the key type of the TLS key. Default `RSA`.
- [ ] `cryostat.agent.webserver.tls.key.pass.file` [`String`]: the path to a file containing the password to unlock the TLS key.
- [ ] `cryostat.agent.webserver.tls.key.pass-charset` [`String`]: the string encoding of the file containing the TLS key password. Default `utf-8`.
- [ ] `cryostat.agent.webserver.tls.key.pass` [`String`]: the TLS key password value. Providing the password as a mounted file is preferred.
- [ ] `cryostat.agent.webserver.tls.cert.alias` [`String`]: the alias for the certificate stored in the HTTPS server keystore. Default `serverCert`.
- [ ] `cryostat.agent.webserver.tls.cert.file` [`String`]: the filepath to the certificate to be stored by the HTTPS server keystore
- [ ] `cryostat.agent.webserver.tls.cert.type` [`String`]: the type of certificate that the HTTPS server keystore will present. Default `X.509`.
- [ ] `cryostat.agent.webserver.credentials.user` [`String`]: the username used for `Basic` authorization on the embedded webserver. Default `user`.
- [ ] `cryostat.agent.webserver.credentials.pass.length` [`int`]: the length of the generated password used for `Basic` authorization on the embedded webserver. Default `24`.
- [ ] `cryostat.agent.webserver.credentials.pass.hash-function` [`String`]: the name of the hash function to use when generating passwords. Default `SHA-256`.
- [ ] `cryostat.agent.app.name` [`String`]: a human-friendly name for this application. Default `cryostat-agent`.
- [ ] `cryostat.agent.app.jmx.port` [`int`]: the JMX RMI port that the application is listening on. The default is to attempt to determine this from the `com.sun.management.jmxremote.port` system property.
- [ ] `cryostat.agent.registration.retry-ms` [`long`]: the duration in milliseconds between attempts to register with the Cryostat server. Default `5000`.
- [ ] `cryostat.agent.registration.jmx.ignore` [`boolean`]: if the Agent detects that the host JVM has its JMX server enabled, then setting this property to `true` will cause the Agent to ignore the JMX server and not publish a JMX Service URL after registering with the Cryostat server. Default `false`.
- [ ] `cryostat.agent.registration.jmx.use-callback-host` [`boolean`]: if the Agent should use the host part of the callback URL when constructing the JMX Service URL for registration. If set to `false` then the URL will contain the automatically detected hostname instead. Default `true`.
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
- [ ] `cryostat.agent.harvester.autoanalyze` [`boolean`]: whether the pushed recordings should be marked for automatic analysis by the Cryostat server. Defaults to `true`.
- [ ] `cryostat.agent.smart-trigger.definitions` [`String[]`]: a comma-separated list of Smart Trigger definitions to load at startup. Defaults to the empty string: no Smart Triggers.
- [ ] `cryostat.agent.smart-trigger.evaluation.period-ms` [`long`]: the length of time between Smart Trigger evaluations. Default `1000`.
- [ ] `cryostat.agent.smart-trigger.config.path` [`String`]: a path containing files with smart trigger definitions. Defaults to the empty string: no Path.
- [ ] `cryostat.agent.callback.scheme` [`String`]: An override for the scheme portion of the `cryostat.agent.callback` URL (e.g. `https`).
- [ ] `cryostat.agent.callback.host-name` [`String[]`]: An override for the host portion of the `cryostat.agent.callback` URL. Supports multiple possible host names. The first host name to resolve when paired with `cryostat.agent.callback.domain-name` will be selected. Supports an optional CEL expression that can be used to transform a provided host name. Only the host name is subject to the provided CEL expression, the domain name is not included. Syntax: `<host name>` or `<host name>[cel-expression]`, the latter evaluates the following CEL expression and uses the result as a host name candidate: `'<host name>'.cel-expression`. Example: `host1, hostx[replace("x"\\, "2")]`, the agent will try to resolve host1, followed by host2.
- [ ] `cryostat.agent.callback.domain-name` [`String`]: An override for the domain portion of the `cryostat.agent.callback` URL. This will be appended to a resolvable host name to form the callback URL.
- [ ] `cryostat.agent.callback.port` [`int`]: An override for the port portion of the `cryostat.agent.callback` URL.
- [ ] `cryostat.agent.fleet-sampling-ratio` [`double`]: Used to control the probability of an individual Agent instance registering with the configured Cryostat server. When the Agent initializes it generates a random value from a uniform distribution in `[0, 1]` and compares this values to the sampling ratio. If the value is less than the sampling ratio then the Agent continues to attempt to register, otherwise the Agent aborts its startup sequence and will not register. The intended effect is that for a large deployment of N applications using the Cryostat Agent, with sampling ratio R, then approximately `N*R` will register with the Cryostat server, so that a randomly selected subsampling can be monitored or profiled. Defaults to `Infinity`, which guarantees all instances will attempt to register.
- [ ] `rht.insights.java.opt-out` [`boolean`]: for the Red Hat build of Cryostat, set this to true to disable data collection for Red Hat Insights. Defaults to `false`. Red Hat Insights data collection is always disabled for community builds of Cryostat.
- [ ] `rht.insights.java.debug` [`boolean`]: for the Red Hat build of Cryostat, set this to true to enable debug logging for the Red Hat Insights Java Agent. Defaults to `false`. Red Hat Insights data collection is always disabled for community builds of Cryostat.

These properties can be set by JVM system properties or by environment variables. For example, the property
`cryostat.agent.baseuri` can be set using `-Dcryostat.agent.baseuri=https://mycryostat.example.com:1234/` or
`CRYOSTAT_AGENT_BASEURI=https://mycryostat.example.com:1234/`. See
[here](https://smallrye.io/smallrye-config/2.11.1/config/environment-variables/) for more detail.
