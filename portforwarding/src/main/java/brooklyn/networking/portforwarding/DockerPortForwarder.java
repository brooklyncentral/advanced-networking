/*
 * Copyright 2013-2014 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.networking.portforwarding;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.docker.DockerApi;
import org.jclouds.docker.domain.Container;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.location.Location;
import brooklyn.location.MachineLocation;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.access.PortForwardManagerImpl;
import brooklyn.location.access.PortMapping;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.JcloudsSshMachineLocation;
import brooklyn.management.ManagementContext;
import brooklyn.networking.subnet.PortForwarder;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.HasNetworkAddresses;
import brooklyn.util.net.Protocol;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;
import com.google.inject.Module;

public class DockerPortForwarder implements PortForwarder {

    private static final Logger log = LoggerFactory.getLogger(DockerPortForwarder.class);

    private PortForwardManager portForwardManager;
    private String dockerEndpoint;
    private String dockerHostname;
    private String dockerIdentity;
    private String dockerCredential;

    public DockerPortForwarder() {
    }

    public DockerPortForwarder(PortForwardManager portForwardManager) {
        this.portForwardManager = portForwardManager;
    }

    @Override
    public void injectManagementContext(ManagementContext managementContext) {
        if (portForwardManager == null) {
            portForwardManager = (PortForwardManager) managementContext.getLocationRegistry().resolve("portForwardManager(scope=global)");
        }
    }
    
    public void init(String dockerHostIp, int dockerHostPort) {
        this.dockerEndpoint = URI.create("http://" + dockerHostIp + ":" + dockerHostPort).toASCIIString();
        this.dockerHostname = dockerHostIp;
        this.dockerIdentity = "notused";
        this.dockerCredential = "notused";
    }

    public void init(URI endpoint) {
        init(endpoint, "notused", "notused");
    }

    public void init(URI endpoint, String identity, String credential) {
        this.dockerEndpoint = endpoint.toASCIIString();
        this.dockerHostname = endpoint.getHost();
        this.dockerIdentity = identity;
        this.dockerCredential = credential;
    }

    public void init(Iterable<? extends Location> locations) {
        Optional<? extends Location> jcloudsLoc = Iterables.tryFind(locations, Predicates.instanceOf(JcloudsLocation.class));
        String provider = (jcloudsLoc.isPresent()) ? ((JcloudsLocation)jcloudsLoc.get()).getProvider() : null;
        String endpoint = (jcloudsLoc.isPresent()) ? ((JcloudsLocation)jcloudsLoc.get()).getEndpoint() : null;
        String identity = (jcloudsLoc.isPresent()) ? ((JcloudsLocation)jcloudsLoc.get()).getIdentity() : null;
        String credential = (jcloudsLoc.isPresent()) ? ((JcloudsLocation)jcloudsLoc.get()).getCredential() : null;
        if (jcloudsLoc.isPresent() && "docker".equals(provider)) {
            init(URI.create(endpoint), identity, credential);
        } else {
            throw new IllegalStateException("Cannot infer docker host URI from locations: "+locations);
        }
    }

    @Override
    public void inject(Entity owner, List<Location> locations) {
        // no-op
    }

    @Override
    public PortForwardManager getPortForwardManager() {
        if (portForwardManager == null) {
            log.warn("Instantiating new PortForwardManager, because ManagementContext not injected into "+this
                    +" (deprecated behaviour that will not be supported in future versions)");
            portForwardManager = new PortForwardManagerImpl();
        }
        return portForwardManager;
    }

    @Override
    public String openGateway() {
        // IP of port-forwarder already exists
        return dockerHostname;
    }

    @Override
    public String openStaticNat(Entity serviceToOpen) {
        throw new UnsupportedOperationException("Can only open individual ports; not static nat with iptables");
    }

    @Override
    public void openFirewallPort(Entity entity, int port, Protocol protocol, Cidr accessingCidr) {
        // TODO If port is already open in docker port-mapping then no-op; otherwise UnsupportedOperationException currently
        if (log.isDebugEnabled()) log.debug("no-op in {} for openFirewallPort({}, {}, {}, {})", new Object[] {this, entity, port, protocol, accessingCidr});
    }

    @Override
    public void openFirewallPortRange(Entity entity, PortRange portRange, Protocol protocol, Cidr accessingCidr) {
        // TODO If port is already open in docker port-mapping then no-op; otherwise UnsupportedOperationException currently
        if (log.isDebugEnabled()) log.debug("no-op in {} for openFirewallPortRange({}, {}, {}, {})", new Object[] {this, entity, portRange, protocol, accessingCidr});
    }

    @Override
    public HostAndPort openPortForwarding(HasNetworkAddresses targetMachine, int targetPort, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr) {

    	String targetIp = Iterables.getFirst(Iterables.concat(targetMachine.getPrivateAddresses(), targetMachine.getPublicAddresses()), null);
        if (targetIp==null) {
            throw new IllegalStateException("Failed to open port-forwarding for machine "+targetMachine+" because its" +
                    " location has no target ip: "+targetMachine);
        }
        HostAndPort targetSide = HostAndPort.fromParts(targetIp, targetPort);
        HostAndPort newFrontEndpoint = openPortForwarding(targetSide, optionalPublicPort, protocol, accessingCidr);
        log.debug("Enabled port-forwarding for {} port {} (VM {}), via {}", new Object[] {targetMachine, targetPort, targetMachine, newFrontEndpoint});
        return newFrontEndpoint;
    }

    @Override
    public HostAndPort openPortForwarding(HostAndPort targetSide, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        // FIXME Does this actually open the port forwarding? Or just record that the port is supposed to be open?
        PortForwardManager pfw = getPortForwardManager();
        PortMapping mapping;
        if (optionalPublicPort.isPresent()) {
            int publicPort = optionalPublicPort.get();
            mapping = pfw.acquirePublicPortExplicit(dockerHostname, publicPort);
        } else {
            mapping = pfw.acquirePublicPortExplicit(dockerHostname, targetSide.getPort());
        }
        if (mapping == null) {
            return HostAndPort.fromParts(dockerHostname, targetSide.getPort());
        } else {
            return HostAndPort.fromParts(dockerHostname, mapping.getPublicPort());
        }
    }

    public Map<Integer, Integer> getPortMappings(MachineLocation targetMachine) {
        ComputeServiceContext context = ContextBuilder.newBuilder("docker")
                .endpoint(dockerEndpoint)
                .credentials(dockerIdentity, dockerCredential)
                .modules(ImmutableSet.<Module>of(new SLF4JLoggingModule(), new SshjSshClientModule()))
                .build(ComputeServiceContext.class);

        DockerApi api = context.unwrapApi(DockerApi.class);
        String containerId = ((JcloudsSshMachineLocation) targetMachine).getJcloudsId();
        Container container = api.getRemoteApi().inspectContainer(containerId);
        context.close();
        Map<Integer, Integer> portMappings = Maps.newLinkedHashMap();
        if(container.getNetworkSettings() == null) return portMappings;
        for(Map.Entry<String, List<Map<String, String>>> entrySet : container.getNetworkSettings().getPorts().entrySet()) {
            String containerPort = Iterables.get(Splitter.on("/").split(entrySet.getKey()), 0);
            String hostPort = Iterables.getOnlyElement(Iterables.transform(entrySet.getValue(),
                    new Function<Map<String, String>, String>() {
                        @Override
                        public String apply(Map<String, String> hostIpAndPort) {
                            return hostIpAndPort.get("HostPort");
                        }
                    }));
            portMappings.put(Integer.parseInt(containerPort), Integer.parseInt(hostPort));
        }
        return portMappings;
    }

    @Override
    public boolean isClient() {
        return false;
    }
}
