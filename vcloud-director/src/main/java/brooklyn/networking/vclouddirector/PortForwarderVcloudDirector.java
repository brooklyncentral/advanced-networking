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
package brooklyn.networking.vclouddirector;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.location.Location;
import brooklyn.location.MachineLocation;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.basic.Machines;
import brooklyn.location.basic.PortRanges;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.management.ManagementContext;
import brooklyn.networking.portforwarding.subnet.SubnetTier;
import brooklyn.networking.subnet.PortForwarder;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.HasNetworkAddresses;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;

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

    /**
     * Returns the mutex to be synchronized on whenever accessing/editing the DNAT rules for a given endpoint.
     */
    private static enum MutexRegistry {
        INSTANCE;
        
        private final Map<String, Object> mutexes = Maps.newLinkedHashMap();
        
        public Object getMutexFor(String endpoint) {
            synchronized (mutexes) {
                Object mutex = mutexes.get(endpoint);
                if (mutex == null) {
                    mutex = new Object();
                    mutexes.put(endpoint, mutex);
                }
                return mutex;
            }
        }
    }
    
    private PortForwardManager portForwardManager;

    private SubnetTier subnetTier;

    private JcloudsLocation jcloudsLocation;

    // Always access via #getService(), to support rebind
    private volatile transient NatService service;

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
    }

    @Override
    public PortForwardManager getPortForwardManager() {
        return portForwardManager;
    }

    @Override
    public void inject(Entity owner, List<Location> locations) {
        subnetTier = (SubnetTier) owner;
        jcloudsLocation = (JcloudsLocation) Iterables.find(locations, Predicates.instanceOf(JcloudsLocation.class));
        service = newService();
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
            throw new IllegalStateException("Failed to open port-forarding for machine "+targetVm+" because its location has no target ip: "+targetVm);
        }

        HostAndPort targetSide = HostAndPort.fromParts(targetIp, targetPort);
        return openPortForwarding(targetSide, optionalPublicPort, protocol, accessingCidr);
    }
    
    @Override
    public HostAndPort openPortForwarding(HostAndPort target, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        // TODO should associate ip:port with PortForwardManager; but that takes location param
        //      getPortForwardManager().associate(publicIp, publicPort, targetVm, targetPort);
        // TODO Could check old mapping, and re-use that public port
        // TODO Pass cidr in vcloud-director call
        PortForwardManager pfw = getPortForwardManager();
        String publicIp = subnetTier.getConfig(NETWORK_PUBLIC_IP);

        int publicPort;
        if (optionalPublicPort.isPresent()) {
            publicPort = optionalPublicPort.get();
        } else {
            publicPort = pfw.acquirePublicPort(publicIp);
        }
        
        try {
            HostAndPort result = getService().openPortForwarding(new PortForwardingConfig()
                    .publicIp(publicIp)
                    .protocol(Protocol.TCP)
                    .target(target)
                    .publicPort(publicPort));
            LOG.debug("Enabled port-forwarding for {}, via {}, on ", new Object[] {target, result, subnetTier});
            return result;
        } catch (IllegalArgumentException e) {
            // Can get this if the publicIp is not valid for this network.
            throw Exceptions.propagate(e);
        } catch (Exception e) {
            LOG.error("Failed creating port forwarding rule on "+this+" to "+target, e);
            // it might already be created, so don't crash and burn too hard!
            return HostAndPort.fromParts(publicIp, publicPort);
        }
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
        HostAndPort target = HostAndPort.fromParts(targetIp, targetPort);
        String publicIp = subnetTier.getConfig(NETWORK_PUBLIC_IP);
        
        try {
            HostAndPort result = getService().closePortForwarding(new PortForwardingConfig()
                    .publicIp(publicIp)
                    .protocol(Protocol.TCP)
                    .target(target)
                    .publicPort(publicPort));
            LOG.debug("Deleted port-forwarding for {}, via {}, on ", new Object[]{target, result, subnetTier});
        } catch (Exception e) {
            LOG.error("Failed deleting port forwarding rule on " + this + " to " + target, e);
            // it might not be created, so don't crash and burn too hard!
        }
    }

    @Override
    public boolean isClient() {
        return false;
    }

    // For rebind, always access via getter so can recreate the service after rebind
    private NatService getService() {
        if (service == null) {
            service = newService();
        }
        return service;
    }
    
    private NatService newService() {
        String endpoint = jcloudsLocation.getEndpoint();

        // jclouds endpoint has suffix "/api"; but VMware SDK wants it without "api"
        String convertedUri;
        try {
            URI uri = URI.create(endpoint);
            convertedUri = new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (URISyntaxException e) {
            throw Exceptions.propagate(e);
        } 

        return NatService.builder()
                .identity(jcloudsLocation.getIdentity())
                .credential(jcloudsLocation.getCredential())
                .endpoint(convertedUri)
                .mutex(MutexRegistry.INSTANCE.getMutexFor(endpoint))
                .build();
    }
}
