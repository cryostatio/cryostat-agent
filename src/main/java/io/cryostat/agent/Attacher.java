/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.agent;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.cryostat.agent.shaded.ShadeLogger;

import net.bytebuddy.agent.ByteBuddyAgent;

class Attacher {

    private final Set<String> watchIncludeKeywords = new HashSet<>();
    private final Set<JavaProcessInfo> watchedDescriptors = new HashSet<>();

    static final String ALL_PIDS = "*";
    static final String AUTO_ATTACH_PID = "0";

    void attach(Agent agent) throws Exception {
        List<String> pids = getAttachPid(agent.pid);
        if (pids.isEmpty()) {
            throw new IllegalStateException("No candidate JVM PIDs");
        }
        String agentmainArg =
                new AgentArgs(
                                agent.properties,
                                String.join(
                                        ",",
                                        Optional.ofNullable(agent.smartTriggers).orElse(List.of())))
                        .toAgentMain();
        if (agent.watch) {
            this.watchIncludeKeywords.addAll(agent.watchIncludeKeywords);
            startWatch(agentmainArg);
            return;
        }

        List<JavaProcessInfo> vmds = getAttachDescriptors(agent.pid);
        if (vmds.isEmpty()) {
            throw new IllegalStateException("No candidate JVM PIDs");
        }
        tryAttachToDescriptors(agentmainArg, vmds, vmds.size() > 1);
    }

    private void startWatch(String agentMainArg) throws Exception {
        Predicate<JavaProcessInfo> p =
                (watchIncludeKeywords == null || watchIncludeKeywords.isEmpty())
                        ? v -> true
                        : v ->
                                watchIncludeKeywords.stream()
                                        .anyMatch(
                                                k ->
                                                        Optional.ofNullable(v.displayName())
                                                                .orElse("")
                                                                .toLowerCase()
                                                                .strip()
                                                                .contains(k.toLowerCase().strip()));
        while (!Thread.currentThread().isInterrupted()) {
            Set<JavaProcessInfo> observedDescriptors =
                    getAttachDescriptors(ALL_PIDS).stream().filter(p).collect(Collectors.toSet());
            observedDescriptors.removeAll(watchedDescriptors);
            tryAttachToDescriptors(agentMainArg, observedDescriptors, true);
            watchedDescriptors.addAll(observedDescriptors);
            Thread.sleep(500); // TODO make configurable
        }
    }

    private static List<JavaProcessInfo> getAttachDescriptors(String pidSpec) {
        List<JavaProcessInfo> vms = ProcessDiscovery.listJavaProcesses();
        Predicate<JavaProcessInfo> vmFilter;
        if (ALL_PIDS.equals(pidSpec)) {
            long ownId = ProcessHandle.current().pid();
            vmFilter = vmd -> !Objects.equals(String.valueOf(ownId), vmd.id());
        } else if (pidSpec == null || AUTO_ATTACH_PID.equals(pidSpec)) {
            if (vms.size() > 2) { // one of them is ourself
                throw new IllegalStateException(
                        String.format(
                                "Too many available virtual machines. Auto-attach only progresses"
                                        + " if there is one candidate. VMs: %s",
                                vms));
            } else if (vms.size() < 2) {
                throw new IllegalStateException(
                        String.format(
                                "Too few available virtual machines. Auto-attach only progresses if"
                                        + " there is one candidate. VMs: %s",
                                vms));
            }
            long ownId = ProcessHandle.current().pid();
            vmFilter = vmd -> !Objects.equals(String.valueOf(ownId), vmd.id());
        } else {
            vmFilter = vmd -> pidSpec.equals(vmd.id());
        }
        return vms.stream().filter(vmFilter).collect(Collectors.toList());
    }

    private void tryAttachToDescriptors(
            String agentMainArg, Collection<JavaProcessInfo> vmds, boolean suppressFailures)
            throws Exception {
        for (JavaProcessInfo vmd : vmds) {
            try {
                tryAttachToDescriptor(agentMainArg, vmd);
            } catch (Exception e) {
                if (suppressFailures) {
                    ShadeLogger.getAnonymousLogger()
                            .severe(String.format("Failed to inject agent into PID %s", vmd.id()));
                    e.printStackTrace(); // TODO print to the logger
                    continue;
                } else {
                    throw e;
                }
            }
        }
    }

    private void tryAttachToDescriptor(String agentmainArg, JavaProcessInfo vmd) throws Exception {
        ShadeLogger.getAnonymousLogger()
                .fine(String.format("Attaching to VM: %s %s", vmd.displayName(), vmd.id()));
        File agentJar = new File(Path.of(selfJarLocation()).toAbsolutePath().toString());
        ShadeLogger.getAnonymousLogger()
                .fine(String.format("Injecting agent into PID %s", vmd.id()));
        ByteBuddyAgent.attach(agentJar, vmd.id(), agentmainArg);
    }

    private static List<String> getAttachPid(String pidSpec) {
        List<JavaProcessInfo> vms = ProcessDiscovery.listJavaProcesses();
        Predicate<JavaProcessInfo> vmFilter;
        if (ALL_PIDS.equals(pidSpec)) {
            vmFilter = vmd -> true;
        } else if (pidSpec == null || AUTO_ATTACH_PID.equals(pidSpec)) {
            if (vms.size() > 2) { // one of them is ourself
                throw new IllegalStateException(
                        String.format(
                                "Too many available virtual machines. Auto-attach only progresses"
                                        + " if there is one candidate. VMs: %s",
                                vms));
            } else if (vms.size() < 2) {
                throw new IllegalStateException(
                        String.format(
                                "Too few available virtual machines. Auto-attach only progresses if"
                                        + " there is one candidate. VMs: %s",
                                vms));
            }
            long ownId = ProcessHandle.current().pid();
            vmFilter = vmd -> !Objects.equals(String.valueOf(ownId), vmd.id());
        } else {
            vmFilter = vmd -> pidSpec.equals(vmd.id());
        }
        return vms.stream()
                .filter(vmFilter)
                .peek(
                        vmd ->
                                ShadeLogger.getAnonymousLogger()
                                        .fine(
                                                String.format(
                                                        "Attaching to VM: %s %s",
                                                        vmd.displayName(), vmd.id())))
                .map(JavaProcessInfo::id)
                .collect(Collectors.toList());
    }

    static URI selfJarLocation() throws URISyntaxException {
        return Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
    }
}
