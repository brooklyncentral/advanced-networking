/*
 * Copyright 2013-2015 by Cloudsoft Corporation Limited
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
package brooklyn.networking.vclouddirector;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.location.Machines;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.core.objs.BrooklynObjectInternal.ConfigurationSupportInternal;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.HasNetworkAddresses;
import org.apache.brooklyn.util.net.Protocol;

import brooklyn.networking.common.subnet.PortForwarder;
import brooklyn.networking.subnet.SubnetTier;

public class PortForwarderVcloudDirector implements PortForwarder {

    /*
     * TODO 
     * - How does the extra VM-provisioning config (e.g. networkId etc) get injected into the JcloudsLocation?
     * - 
     */
    
    private static final Logger LOG = LoggerFactory.getLogger(PortForwarderVcloudDirector.class);

    public static final ConfigKey<String> NETWORK_ID = ConfigKeys.newStringConfigKey(
            "advancednetworking.vcloud.network.id",
            "Optionally specify the id of an existing network");

    public static final ConfigKey<String> NETWORK_NAME = ConfigKeys.newStringConfigKey(
            "advancednetworking.vcloud.network.name",
            "Optionally specify the name of an existing network");

    public static final ConfigKey<String> NETWORK_PUBLIC_IP = ConfigKeys.newStringConfigKey(
            "advancednetworking.vcloud.network.publicip",
            "Optionally specify an existing public IP associated with the network");

    public static final ConfigKey<String> NAT_MICROSERVICE_ENDPOINT = ConfigKeys.newStringConfigKey(
            "advancednetworking.vcloud.network.microservice.endpoint",
            "URL for the vCD NAT micro-service, to be used for updating the Edge Gateway; if absent, will use vcloud directly");
    
    public static final ConfigKey<Boolean> NAT_MICROSERVICE_AUTO_ALLOCATES_PORT = ConfigKeys.newBooleanConfigKey(
            "advancednetworking.vcloud.network.microserviceAutoAllocatesPort",
            "Whether the vCD NAT micro-service auto-allocates the port, or if an explicit public port should always be passed to it. "
                    + "Defaults to true.",
            true);
    
    public static final ConfigKey<PortRange> PORT_RANGE = ConfigKeys.newConfigKey(
            PortRange.class,
            "advancednetworking.vcloud.network.portRange",
            "Port-range to use for public-side when auto-allocating ports for NAT rules");
    
    private PortForwardManager portForwardManager;

    private SubnetTier subnetTier;

    private JcloudsLocation jcloudsLocation;

    private ManagementContext managementContext;
    
    // To support rebind, always access via #getClient(), #getPortRange() and #getNatMicroserviceAutoAllocatesPorts()
    // For portRange/natMicroserviceAutoAllocatingPorts, this allows re-configuration on restart.
    private volatile transient NatClient client;
    private volatile transient PortRange portRange;
    private volatile transient Boolean natMicroserviceAutoAllocatesPorts;

    public PortForwarderVcloudDirector() {
    }

    public PortForwarderVcloudDirector(PortForwardManager portForwardManager) {
        this.portForwardManager = portForwardManager;
    }

    @Override
    public void injectManagementContext(ManagementContext managementContext) {
        if (portForwardManager == null) {
            portForwardManager = (PortForwardManager) managementContext.getLocationRegistry().resolve("portForwardManager(scope=global)");
        }
        this.managementContext = managementContext;
    }

    @Override
    public PortForwardManager getPortForwardManager() {
        return portForwardManager;
    }

    @Override
    public void inject(Entity owner, List<Location> locations) {
        subnetTier = (SubnetTier) owner;
        jcloudsLocation = (JcloudsLocation) Iterables.find(locations, Predicates.instanceOf(JcloudsLocation.class));
        getClient(); // force load of client: fail fast if configuration is missing
        getPortRange();
        getNatMicroserviceAutoAllocatesPorts();
    }

    @Override
    public String openGateway() {
        // TODO Handle case where publicIp not already supplied
        String publicIp = subnetTier.getConfig(NETWORK_PUBLIC_IP);
        return publicIp;
    }

    @Override
    public String openStaticNat(Entity serviceToOpen) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void openFirewallPort(Entity entity, int port, Protocol protocol, Cidr accessingCidr) {
        openFirewallPortRange(entity, PortRanges.fromInteger(port), protocol, accessingCidr);
    }

    @Override
    public void openFirewallPortRange(Entity entity, PortRange portRange, Protocol protocol, Cidr accessingCidr) {
        throw new UnsupportedOperationException();
    }

    public HostAndPort openPortForwarding(MachineLocation targetVm, int targetPort, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr) {
        // TODO assumes targetVM is in the JcloudsLocation injected earlier; could check
        return openPortForwarding((HasNetworkAddresses)targetVm, targetPort, optionalPublicPort, protocol, accessingCidr);
    }

    @Override
    public HostAndPort openPortForwarding(HasNetworkAddresses targetVm, int targetPort, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr) {
        String targetIp = Iterables.getFirst(Iterables.concat(targetVm.getPrivateAddresses(), targetVm.getPublicAddresses()), null);
        if (targetIp==null) {
            throw new IllegalStateException("Failed to open port-forwarding for machine "+targetVm+" because its " +
                    "location has no target ip: "+targetVm);
        }

        HostAndPort targetSide = HostAndPort.fromParts(targetIp, targetPort);
        return openPortForwarding(targetSide, optionalPublicPort, protocol, accessingCidr);
    }
    
    @Override
    public HostAndPort openPortForwarding(HostAndPort targetEndpoint, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        // TODO should associate ip:port with PortForwardManager; but that takes location param
        //      getPortForwardManager().associate(publicIp, publicPort, targetVm, targetPort);
        // TODO Could check old mapping, and re-use that public port
        // TODO Pass cidr in vcloud-director call
        String publicIp = subnetTier.getConfig(NETWORK_PUBLIC_IP);

        HostAndPort publicEndpoint;
        PortRange portRangeToUse;
        if (optionalPublicPort.isPresent()) {
            publicEndpoint = HostAndPort.fromParts(publicIp, optionalPublicPort.get());
            portRangeToUse = null;
        } else if (getNatMicroserviceAutoAllocatesPorts()) {
            publicEndpoint = HostAndPort.fromString(publicIp);
            portRangeToUse = getPortRange();
        } else {
            PortForwardManager pfw = getPortForwardManager();
            int publicPort = pfw.acquirePublicPort(publicIp);
            publicEndpoint = HostAndPort.fromParts(publicIp, publicPort);
            portRangeToUse = null;
        }
        
        try {
            HostAndPort result = getClient().openPortForwarding(new PortForwardingConfig()
                    .protocol(Protocol.TCP)
                    .publicEndpoint(publicEndpoint)
                    .publicPortRange(portRangeToUse)
                    .targetEndpoint(targetEndpoint));
            
            // TODO Work around for old vCD NAT microservice, which returned empty result
            if (!result.hasPort() && result.getHostText().equals("")) {
                if (publicEndpoint.hasPort()) {
                    LOG.warn("[DEPRECATED] NAT Rule addition returned endpoint '{}'; probably old micro-service version; "
                            + "assuming result is {}->{} via {}", new Object[] {result, publicEndpoint, targetEndpoint, subnetTier});
                    result = publicEndpoint;
                } else {
                    throw new IllegalStateException("Invalid result for NAT Rule addition, returned endpoint ''; "
                            + "cannot infer actual result as no explicit port requested for "
                            + publicEndpoint+"->"+targetEndpoint+" via "+subnetTier);
                }                    
            }
            
            LOG.debug("Enabled port-forwarding for {}, via {}, on ", new Object[] {targetEndpoint, result, subnetTier});
            return result;
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            LOG.info("Failed creating port forwarding rule on "+this+": "+publicEndpoint+"->"+targetEndpoint+"; rethrowing", e);
            throw Exceptions.propagate(e);
        }
    }

    @Override
    public boolean closePortForwarding(HostAndPort targetEndpoint, HostAndPort publicEndpoint, Protocol protocol) {
        try {
            HostAndPort result = getClient().closePortForwarding(new PortForwardingConfig()
                    .publicEndpoint(publicEndpoint)
                    .targetEndpoint(targetEndpoint)
                    .protocol(Protocol.TCP));
            LOG.debug("Deleted port-forwarding for {}, via {}, on {}", new Object[]{targetEndpoint, result, subnetTier});
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to close port-forwarding rule on " + this + " to " + targetEndpoint, e);
            return false;
        }

    }
    
    @Override
    public boolean closePortForwarding(HasNetworkAddresses targetMachine, int targetPort, HostAndPort publicEndpoint, Protocol protocol) {
        String targetIp = Iterables.getFirst(Iterables.concat(targetMachine.getPrivateAddresses(), targetMachine.getPublicAddresses()), null);
        if (targetIp==null) {
            LOG.warn("Failed to close port-forwarding rule because no IP in {}, on {}: {} -> {}", new Object[] {targetMachine, this, targetPort, publicEndpoint});
            return false;
        }

        return closePortForwarding(HostAndPort.fromParts(targetIp, targetPort), publicEndpoint, protocol);
    }

    /**
     * Deletes the NAT rule for the given port.
     * 
     * Expects caller to call {@link PortForwardManager#forgetPortMapping(String, int)}
     */
    public void closePortForwarding(EntityAndAttribute<Integer> privatePort, int publicPort) {
        Entity entity = privatePort.getEntity();
        Integer targetPort = privatePort.getValue();
        MachineLocation machine = Machines.findUniqueMachineLocation(entity.getLocations()).get();
        String targetIp = Iterables.getFirst(Iterables.concat(machine.getPrivateAddresses(), machine.getPublicAddresses()), null);
        if (targetIp == null) {
            throw new IllegalStateException("Failed to close port-forwarding for machine " + machine + " because its location has no target ip: " + machine);
        }
        HostAndPort targetSide = HostAndPort.fromParts(targetIp, targetPort);
        HostAndPort publicSide = HostAndPort.fromParts(subnetTier.getConfig(NETWORK_PUBLIC_IP), publicPort);
        
        closePortForwarding(targetSide, publicSide, Protocol.TCP);
    }

    @Override
    public boolean isClient() {
        return false;
    }

    // For rebind, always access via getter so can recreate the service after rebind
    protected NatClient getClient() {
        if (client == null) {
            String microserviceUrl = subnetTier.config().get(NAT_MICROSERVICE_ENDPOINT);
            if (microserviceUrl == null) {
                microserviceUrl = jcloudsLocation.config().get(NAT_MICROSERVICE_ENDPOINT);
            }
            if (microserviceUrl == null) {
                microserviceUrl = managementContext.getConfig().getConfig(NAT_MICROSERVICE_ENDPOINT);
            }

            if (microserviceUrl == null) {
                client = new NatDirectClient(jcloudsLocation);
            } else {
                client = new NatMicroserviceClient(microserviceUrl, jcloudsLocation);
            }
        }
        return client;
    }
    
    protected boolean getNatMicroserviceAutoAllocatesPorts() {
        if (natMicroserviceAutoAllocatesPorts == null) {
            Maybe<Object> raw = ((ConfigurationSupportInternal)subnetTier.config()).getRaw(NAT_MICROSERVICE_AUTO_ALLOCATES_PORT);
            if (raw.isPresent()) {
                natMicroserviceAutoAllocatesPorts = subnetTier.config().get(NAT_MICROSERVICE_AUTO_ALLOCATES_PORT);
            }
            if (natMicroserviceAutoAllocatesPorts == null) {
                natMicroserviceAutoAllocatesPorts = managementContext.getConfig().getConfig(NAT_MICROSERVICE_AUTO_ALLOCATES_PORT);
            }
        }
        return Boolean.TRUE.equals(natMicroserviceAutoAllocatesPorts);
    }
    
    protected PortRange getPortRange() {
        if (portRange == null) {
            portRange = subnetTier.getConfig(PORT_RANGE);
            if (portRange == null && jcloudsLocation != null) {
                portRange = jcloudsLocation.getConfig(PORT_RANGE);
            }
            if (portRange == null) {
                portRange = managementContext.getConfig().getConfig(PORT_RANGE);
            }
            if (portRange == null) {
                int startingPort = managementContext.getConfig().getConfig(PortForwardManager.PORT_FORWARD_MANAGER_STARTING_PORT);
                portRange = PortRanges.fromString(startingPort+"+");
            }
        }
        return portRange;
    }
}
