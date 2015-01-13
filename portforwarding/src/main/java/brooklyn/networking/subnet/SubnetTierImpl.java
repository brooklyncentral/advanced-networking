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
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.jclouds.compute.domain.NodeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.annotation.Effector;
import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.trait.StartableMethods;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.Sensors;
import brooklyn.location.Location;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.basic.PortRanges;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.networking.JcloudsPortForwarderExtension;
import brooklyn.management.internal.CollectionChangeListener;
import brooklyn.management.internal.ManagementContextInternal;
import brooklyn.networking.AttributeMunger;
import brooklyn.networking.portforwarding.subnet.JcloudsPortforwardingSubnetLocation;
import brooklyn.policy.EnricherSpec;
import brooklyn.util.config.ConfigBag;
import brooklyn.util.javalang.Reflections;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.HasNetworkAddresses;
import brooklyn.util.net.Protocol;
import brooklyn.util.text.Strings;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;

public class SubnetTierImpl extends AbstractEntity implements SubnetTier {

    private static final Logger log = LoggerFactory.getLogger(SubnetTierImpl.class);
    
    public static AttributeSensor<PortForwardManager> PORT_FORWARD_MANAGER_LIVE = Sensors.newSensor(PortForwardManager.class, "subnet.portForwardManager.live");
    public static AttributeSensor<PortForwarder> PORT_FORWARDER_LIVE = Sensors.newSensor(PortForwarder.class, "subnet.portForwarder.live");
    
    private transient volatile AttributeMunger _attributeMunger;
    private transient volatile PortForwarderAsync _portForwarderAsync;
    private transient volatile JcloudsPortForwarderExtension _portForwarderExtension;

    protected transient Set<Entity> portMappedEntities = Collections.synchronizedSet(Sets.<Entity>newLinkedHashSet());
    
    @Override
    public void init() {
        super.init();

        PortForwarder pf = checkNotNull(getPortForwarder(), "portForwarder");

        PortForwardManager pfmFromConf = getConfig(PORT_FORWARDING_MANAGER);
        PortForwardManager pfmFromPf = pf.getPortForwardManager();
        PortForwardManager pfmLive;
        
        // Get the PortForwardManager. This could be supplied as config, or via the PortForwarder, or if both
        // are null then we'll instantiate a new one. We will ensure that config(PORT_FORWARDING_MANAGER) is
        // set (with a client, so safe for inheritance), and ensure that attribute(PORT_FORWARD_MANAGER_LIVE)
        // is also set.
        // TODO Can greatly simplify this, now that PortForwardManager is a location. Don't need to separate
        // the "live" and config.
        if (pfmFromConf == null) {
            if (pfmFromPf == null) {
                log.trace("Subnet tier "+this+" has no PortForwardManager supplied; retrieving portForwardManager(scope=global)");
                pfmLive = (PortForwardManager) getManagementContext().getLocationRegistry().resolve("portForwardManager(scope=global)");
            } else {
                log.trace("Subnet tier "+this+" using "+pfmFromPf+", retrieved from PortForwarder "+pf);
                pfmLive = pfmFromPf;
            }
        } else {
            if (pfmFromPf != null) {
                // check if they are the same; warn if they are not
                if (!pfmFromConf.getId().equals(pfmFromPf.getId())) {
                    log.warn("Conflicting PortForwardManagers supplied for {}; config is {}; port forwarder "
                            + "{} has {}; using config value", new Object[] {this, pfmFromConf, pf, pfmFromPf});
                }
            }
            log.trace("Subnet tier "+this+" using "+pfmFromConf+", retrieved from entity config");
            pfmLive = pfmFromConf;
        }
        
        setConfig(PORT_FORWARDING_MANAGER, pfmLive);
        setAttribute(PORT_FORWARD_MANAGER_LIVE, pfmLive);
        setAttribute(PORT_FORWARDER_LIVE, pf);
        if (log.isDebugEnabled()) log.debug("Subnet tier {} using PortForwardManager {}, and port forwarder {}", new Object[] {this, pfmLive, pf});

        // TODO For rebind, would require to re-register this; same for portMappedEntities field
        CollectionChangeListener<Entity> descendentsChangedListener = new CollectionChangeListener<Entity>() {
            @Override public void onItemAdded(Entity item) {
                SubnetTierImpl.this.onEntityAdded(item);
            }
            @Override public void onItemRemoved(Entity item) {
            }
        };
        ((ManagementContextInternal) getManagementContext()).addEntitySetListener(descendentsChangedListener);
        rescanDescendants();
    }
    
    private void rescanDescendants() {
        for (Entity descendant : Entities.descendants(this)) {
            if (!portMappedEntities.contains(descendant)) {
                onEntityAdded(descendant);
            }
        }
    }
    // Tracking entities so can automatically set up port-forwarding as required
    private void onEntityAdded(Entity entity) {
        if (Entities.isAncestor(entity, SubnetTierImpl.this) && !portMappedEntities.contains(entity)) {
            Iterable<AttributeSensor<Integer>> attributes = entity.getConfig(PUBLICLY_FORWARDED_PORTS);
            if (attributes != null) {
                portMappedEntities.add(entity);
                for (AttributeSensor<Integer> attribute : attributes) {
                    AttributeSensor<String> mappedAttribute = Sensors.newStringSensor("mapped."+attribute.getName());
                    openPortForwardingAndAdvertise(
                            EntityAndAttribute.supplier(entity, attribute), 
                            Optional.<Integer>absent(), 
                            Protocol.TCP, 
                            Cidr.UNIVERSAL, 
                            EntityAndAttribute.supplier(entity, mappedAttribute));
                }
            }
        }
    }

    protected JcloudsPortForwarderExtension newJcloudsPortForwarderExtension() {
        return new SubnetTierJcloudsPortForwarderExtension(
                PortForwarderClient.fromMethodOnEntity(this, "getPortForwarder"), 
                getPortForwardManager());
    }

    public static class SubnetTierJcloudsPortForwarderExtension implements JcloudsPortForwarderExtension {
        protected final PortForwarder pf;
        protected final PortForwardManager pfm;

        public SubnetTierJcloudsPortForwarderExtension(PortForwarder pf, PortForwardManager pfm) {
            if (!pf.isClient()) throw new IllegalStateException("Must take a PortForwarder client");
            this.pf = pf;
            this.pfm = pfm;
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
            HostAndPort result = pf.openPortForwarding(
                    hasNetworkAddresses,
                    node.getLoginPort(),
                    optionalPublicPort,
                    Protocol.TCP,
                    Cidr.UNIVERSAL);
            pfm.associate(node.getId(), result, targetPort);
            return result;
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
                    .configure(JcloudsLocation.USE_PORT_FORWARDING, getConfig(MANAGEMENT_ACCESS_REQUIRES_PORT_FORWARDING))
                    .configure(JcloudsLocation.PORT_FORWARDER, getPortForwarderExtension())
                    .configure(JcloudsLocation.PORT_FORWARDING_MANAGER, getPortForwardManager())
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
        PortForwarder portForwarder = getPortForwarder();
        portForwarder.inject(getProxy(), ImmutableList.copyOf(locations));
        
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
        
        if (result == null) {
            String type = getConfig(PORT_FORWARDER_TYPE);
            if (Strings.isNonBlank(type)) {
                log.trace("Subnet tier "+this+" instantiating new PortForwarder of type "+type);
                ClassLoader catalogClassLoader = getManagementContext().getCatalog().getRootClassLoader();
                Optional<PortForwarder> portForwarderByType = Reflections.invokeConstructorWithArgs(catalogClassLoader, type);
                if (portForwarderByType.isPresent()) {
                    result = portForwarderByType.get();
                } else {
                    throw new IllegalStateException("Failed to create PortForwarder "+type+" for subnet tier "+this);
                }
            }
        }
        if (result != null) {
            result.injectManagementContext(getManagementContext());
            setAttribute(PORT_FORWARDER_LIVE, result);
        }
        return result;
    }

    protected AttributeMunger getAttributeMunger() {
        // AttributeMunger is stateless; if we create two instances then no harm; not worrying about synchronization
        // beyond a volatile field to ensure other threads don't see a partially instantiated object.
        if (_attributeMunger == null) {
            _attributeMunger = new AttributeMunger(this);
        }
        return _attributeMunger;
    }
    
    @Override
    public synchronized PortForwarderAsync getPortForwarderAsync() {
        if (_portForwarderAsync==null) {
            _portForwarderAsync = new PortForwarderAsyncImpl(this, getPortForwarder(), getPortForwardManager());
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
        getAttributeMunger().transformSensorStringReplacingWithPublicAddressAndPort(targetToUpdate, optionalTargetPort, targetsToMatch, replacementSource);
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
