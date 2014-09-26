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
package brooklyn.networking.subnet;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.List;

import org.jclouds.compute.domain.NodeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.annotation.Effector;
import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.trait.StartableMethods;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.Sensors;
import brooklyn.location.Location;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.access.PortForwardManagerAuthority;
import brooklyn.location.access.PortForwardManagerClient;
import brooklyn.location.basic.PortRanges;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.networking.JcloudsPortForwarderExtension;
import brooklyn.networking.AttributeMunger;
import brooklyn.networking.portforwarding.subnet.JcloudsPortforwardingSubnetLocation;
import brooklyn.networking.subnet.PortForwarder;
import brooklyn.networking.subnet.PortForwarderAsync;
import brooklyn.networking.subnet.PortForwarderAsyncImpl;
import brooklyn.networking.subnet.PortForwarderClient;
import brooklyn.policy.EnricherSpec;
import brooklyn.util.config.ConfigBag;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

public class SubnetTierImpl extends AbstractEntity implements SubnetTier {

    public static AttributeSensor<PortForwardManager> PORT_FORWARD_MANAGER_LIVE = Sensors.newSensor(PortForwardManager.class, "subnet.portForwardManager.live");
    public static AttributeSensor<PortForwarder> PORT_FORWARDER_LIVE = Sensors.newSensor(PortForwarder.class, "subnet.portForwarder.live");

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(SubnetTierImpl.class);

    protected AttributeMunger attributeMunger;
    protected transient PortForwarderAsync _portForwarderAsync;
    protected transient JcloudsPortForwarderExtension _portForwarderExtension;

    @Override
    public void init() {
        super.init();

        PortForwarder pf = checkNotNull(getPortForwarder(), "portForwarder");
        PortForwardManager pfm = getConfig(PORT_FORWARDING_MANAGER);
        if (pfm == null && pf.getPortForwardManager() == null) {
            pfm = new PortForwardManagerAuthority();
        }
        if (pfm!=null) {
            if (!pfm.isClient()) {
                // ensure we really are the owner
                ((PortForwardManagerAuthority)pfm).injectOwningEntity(this);
                // store this authoritative source here, but set the config to be the client safe for inheriting
                setConfig(PORT_FORWARDING_MANAGER, PortForwardManagerClient.fromAttributeOnEntity(this, PORT_FORWARD_MANAGER_LIVE));
            }
            setAttribute(PORT_FORWARD_MANAGER_LIVE, pfm);
        }

        if (!pf.isClient()) {
            // set the config to be the client safe for inheriting
            setConfig(PORT_FORWARDER, PortForwarderClient.fromAttributeOnEntity(this, PORT_FORWARDER_LIVE));
        }
        setAttribute(PORT_FORWARDER_LIVE, pf);

        attributeMunger = new AttributeMunger(this);
    }

    protected JcloudsPortForwarderExtension newJcloudsPortForwarderExtension() {
        return new SubnetTierJcloudsPortForwarderExtension(PortForwarderClient.fromMethodOnEntity(this, "getPortForwarder"));
    }

    public static class SubnetTierJcloudsPortForwarderExtension implements JcloudsPortForwarderExtension {
        protected final PortForwarder pf;

        public SubnetTierJcloudsPortForwarderExtension(PortForwarder pf) {
            if (!pf.isClient()) throw new IllegalStateException("Must take a client");
            this.pf = pf;
        }

        @Override
        public HostAndPort openPortForwarding(final NodeMetadata node, int targetPort, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        	HasNetworkAddresses hasNetworkAddresses = new HasNetworkAddresses() {
				@Override public String getHostname() {
					return node.getHostname();
				}
				@Override public Set<String> getPublicAddresses() {
					return node.getPublicAddresses();
				}
				@Override public Set<String> getPrivateAddresses() {
					return node.getPrivateAddresses();
				}
				@Override
				public String toString() {
					return node.toString();
				}
        	};
            return pf.openPortForwarding(
                    hasNetworkAddresses,
                    node.getLoginPort(),
                    optionalPublicPort,
                    Protocol.TCP,
                    Cidr.UNIVERSAL);
        }
    }

    protected Location customizeLocation(Location location) {
        JcloudsLocation jl = null;
        if (location instanceof JcloudsLocation) {
            jl = (JcloudsLocation) location;
        }
        if (jl != null) {
            return jl.newSubLocation(JcloudsPortforwardingSubnetLocation.class, new ConfigBag()
                    // put these fields on the location so it has the info it needs to create the subnet
                    .configure(JcloudsLocation.USE_PORT_FORWARDING, true)
                    .configure(JcloudsLocation.PORT_FORWARDER, getPortForwarderExtension())
                    .configure(JcloudsLocation.PORT_FORWARDING_MANAGER, PortForwardManagerClient.fromAttributeOnEntity(this, PORT_FORWARD_MANAGER_LIVE))
                    .configure(JcloudsPortforwardingSubnetLocation.PORT_FORWARDER, PortForwarderClient.fromAttributeOnEntity(this, PORT_FORWARDER_LIVE))
                    .getAllConfig());
        } else {
            return location;
        }
    }

    protected void openAndRegisterGateway() {
        String gatewayIp = getPortForwarder().openGateway();
        PortForwardManager pfw = getPortForwardManager();
        pfw.recordPublicIpHostname(gatewayIp, gatewayIp);
    }

    public void start(Collection<? extends Location> locations) {
        addLocations(locations);
        Location origLoc = Iterables.getOnlyElement(locations);
        Location customizedLoc = customizeLocation(origLoc);

        Collection<Location> customizedLocations = ImmutableList.of(customizedLoc);
        openAndRegisterGateway();

        StartableMethods.start(this, customizedLocations);
    }

    @Override
    public synchronized PortForwardManager getPortForwardManager() {
        PortForwardManager pfm = getAttribute(PORT_FORWARD_MANAGER_LIVE);
        if (pfm!=null) return pfm;
        return getPortForwarder().getPortForwardManager();
    }

    @Override
    public synchronized PortForwarder getPortForwarder() {
        PortForwarder pf = getAttribute(PORT_FORWARDER_LIVE);
        if (pf != null) return pf;
        
        PortForwarder result = getConfig(PORT_FORWARDER);
        if (result != null) return result;
        
        String type = getConfig(PORT_FORWARDER_TYPE);
        ClassLoader catalogClassLoader = getManagementContext().getCatalog().getRootClassLoader();
        if (Strings.isNonBlank(type)) {
            Optional<PortForwarder> portForwarderByType = Reflections.invokeConstructorWithArgs(catalogClassLoader, type);
            if (portForwarderByType.isPresent()) {
                result = portForwarderByType.get();
            } else {
                throw new IllegalStateException("Failed to create PortForwarder "+type+" for subnet tier "+this);
            }
        }
        return result;
    }

    @Override
    public synchronized PortForwarderAsync getPortForwarderAsync() {
        if (_portForwarderAsync==null) {
            _portForwarderAsync = new PortForwarderAsyncImpl(this, getPortForwarder());
        }
        return _portForwarderAsync;
    }

    @Override
    public synchronized JcloudsPortForwarderExtension getPortForwarderExtension() {
        if (_portForwarderExtension==null) {
            _portForwarderExtension = newJcloudsPortForwarderExtension();
        }
        return _portForwarderExtension;
    }


    @Override
    @Effector(description = "Stop the process/service represented by an entity")
    public void stop() {
        StartableMethods.stop(this);

        // TODO delete network
    }

    @Override
    @Effector(description = "Restart the process/service represented by an entity")
    public void restart() {
        stop();
        start(getLocations());
    }

    @Override
    public void transformSensorStringReplacingWithPublicAddressAndPort(
            final EntityAndAttribute<String> targetToUpdate,
            final Optional<EntityAndAttribute<Integer>> optionalTargetPort,
            final EntityAndAttribute<String> replacementSource) {
        List<AttributeSensor<String>> targetsToMatch = ImmutableList.of(
                SoftwareProcess.HOSTNAME,
                SoftwareProcess.ADDRESS,
                SubnetTier.PRIVATE_HOSTNAME,
                SUBNET_HOSTNAME_SENSOR);
        attributeMunger.transformSensorStringReplacingWithPublicAddressAndPort(targetToUpdate, optionalTargetPort, targetsToMatch, replacementSource);
    }

    @Override
    public EnricherSpec<?> uriTransformingEnricher(AttributeSensor<String> original, AttributeSensor<String> target) {
        return SubnetEnrichers.uriTransformingEnricher(this, original, target);
    }

    @Override
    public EnricherSpec<?> uriTransformingEnricher(EntityAndAttribute<String> original, AttributeSensor<String> target) {
        return SubnetEnrichers.uriTransformingEnricher(this, original, target);
    }

    @Override
    public EnricherSpec<?> hostAndPortTransformingEnricher(AttributeSensor<Integer> originalPort, AttributeSensor<String> target) {
        return SubnetEnrichers.hostAndPortTransformingEnricher(this, originalPort, target);
    }

    @Override
    public EnricherSpec<?> hostAndPortTransformingEnricher(EntityAndAttribute<Integer> originalPort, AttributeSensor<String> target) {
        return SubnetEnrichers.hostAndPortTransformingEnricher(this, originalPort, target);
    }

    @Override
    public void openPublicIp(EntityAndAttribute<String> whereToAdvertiseHostname) {
        getPortForwarderAsync().openGatewayAsync(whereToAdvertiseHostname);
    }

    @Override
    public void openFirewallPort(EntityAndAttribute<String> publicIp, int port, Protocol protocol, Cidr accessingCidr) {
        getPortForwarderAsync().openFirewallPortAsync(publicIp, port, protocol, accessingCidr);
    }

    @Override
    public void openFirewallPortRange(EntityAndAttribute<String> publicIp, int lowerBoundPort, int upperBoundPort, Protocol protocol, Cidr accessingCidr) {
        getPortForwarderAsync().openFirewallPortRangeAsync(publicIp, new PortRanges.LinearPortRange(lowerBoundPort, upperBoundPort),
                protocol, accessingCidr);
    }

    @Override
    public void openPortForwardingAndAdvertise(EntityAndAttribute<Integer> privatePort, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr, EntityAndAttribute<String> whereToAdvertiseEndpoint) {
        getPortForwarderAsync().openPortForwardingAndAdvertise(privatePort, optionalPublicPort, protocol, accessingCidr,
                whereToAdvertiseEndpoint);
    }

}
