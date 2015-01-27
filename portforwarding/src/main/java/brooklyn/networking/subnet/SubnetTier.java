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

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.entity.trait.Startable;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.event.basic.BasicConfigKey;
import brooklyn.location.MachineLocation;
import brooklyn.location.access.BrooklynAccessUtils;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.access.PortForwardManagerClient;
import brooklyn.location.jclouds.networking.JcloudsPortForwarderExtension;
import brooklyn.networking.common.subnet.PortForwarder;
import brooklyn.networking.common.subnet.PortForwarderAsync;
import brooklyn.policy.EnricherSpec;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Protocol;

import com.google.common.annotations.Beta;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;

@Beta
@ImplementedBy(SubnetTierImpl.class)
public interface SubnetTier extends Entity, Startable {

    // TODO Only works for integer ports currently; should also make it work for URLs and HostAndPort (like in clocker)
    // Config should be set on entities within subnet tier, rather than on the subnet tier itself
    public static final ConfigKey<Iterable<AttributeSensor<Integer>>> PUBLICLY_FORWARDED_PORTS = ConfigKeys.newConfigKey(
            new TypeToken<Iterable<AttributeSensor<Integer>>>() {},
            "subnet.publiclyForwardedPorts",
            "Configuration to be set on individual entities that are descendents of SubnetTier; these ports will automatically be opened",
            ImmutableList.<AttributeSensor<Integer>>of());

    public static final ConfigKey<Cidr> SUBNET_CIDR = new BasicConfigKey<Cidr>(Cidr.class,
            "subnet.cidr", "CIDR to use for this subnet", null);

    // TODO This is respected for JcloudsLocation not setting up port-forwarding to 22; but what about
    // BrooklynAccessUtils.getBrooklynAccessibleAddress?
    // See JcloudsPortforwardingSubnetMachineLocation.getSocketEndpointFor
    public static final ConfigKey<Boolean> MANAGEMENT_ACCESS_REQUIRES_PORT_FORWARDING = ConfigKeys.newBooleanConfigKey(
            "subnet.managementnetwork.portforwarding.enabled", 
            "Whether to setup port-forwarding for the management plane to use when subsequently accessing the VM (e.g. over the ssh port)", 
            true);

    @SetFromFlag("portForwarder")
    public static final ConfigKey<PortForwarder> PORT_FORWARDER = ConfigKeys.newConfigKey(
            PortForwarder.class, 
            "subnet.portForwarder", 
            "port forwarding implementation for use at this subnet tier (required, or specify subnet.portForwarder.type)");

    @Beta
    @SetFromFlag("portForwarder")
    public static final ConfigKey<String> PORT_FORWARDER_TYPE = ConfigKeys.newStringConfigKey(
            "subnet.portForwarder.type", 
            "port forwarding implementation type for use at this subnet tier");

    /** optional manager which can be injected for shared visibility of management rules;
     * injection should be a client typically, cf {@link PortForwardManagerClient} */
    @SetFromFlag("portForwardManager")
    public static final ConfigKey<PortForwardManager> PORT_FORWARDING_MANAGER = BrooklynAccessUtils.PORT_FORWARDING_MANAGER;

    public static final AttributeSensor<String> SUBNET_HOSTNAME_SENSOR = new BasicAttributeSensor<String>(String.class, "host.name.subnet", "Subnet-accessible IP/Hostname (for VM's in a subnet)");

    public static final AttributeSensor<String> PUBLIC_HOSTNAME = new BasicAttributeSensor<String>(String.class, "host.service.default.hostname",
            "Publicly addressible hostname or IP used when port forwarding to this subnet (forwarding to internal elements)");

    public static final AttributeSensor<String> DEFAULT_PUBLIC_HOSTNAME_AND_PORT = new BasicAttributeSensor<String>(String.class, "host.service.default.hostnameAndPort",
            "Provides a publicly accessible hostname:port combo for a service");

    // TODO This isn't really a sensor on SubnetTier; we just need to define it somewhere so other things
    // in the subnet can reference/set it on the entities within the subnet. Where is best place to define
    // this? Should we create a constants interface?
    public static final AttributeSensor<String> PRIVATE_HOSTNAME = new BasicAttributeSensor<String>(String.class, "hostname.private",
            "A private hostname or IP within the subnet (used so don't lose private address when transforming hostname etc on an entity)");

    /** replaces default hostname/address and optional port with serviceHost
     * (and optional port as part of that string), to map a service to _outside_ a VPC.
     * <p>
     * e.g. DB advertises 10.255.96.80:3306 which is a shared network address, where
     * port-forwarding is set up at 216.65.40.30:53306. This could maps "mysql://10.255.96.80:3306/"
     * to "mysql://216.65.40.30:53306/", updating the sensor with this new value.
     * <p>
     * By default it will look up attributes like SoftwareProcess.HOSTNAME to find things to be
     * replaced within the {@code targetToUpdate}.
     * <p>
     * NB: target should have a hostname
     *
     * @param targetToUpdate     the entity->sensor to be updated with the transformed value
     * @param optionalTargetPort the entity->sensor giving a suffix, to be replaced as well
     * @param replacementSource  the source of the replacement text
     */
    public void transformSensorStringReplacingWithPublicAddressAndPort(
            final EntityAndAttribute<String> targetToUpdate,
            final Optional<EntityAndAttribute<Integer>> optionalTargetPort,
            final EntityAndAttribute<String> replacementSource);

    /**
     * Builds an enricher that takes the given {@code original} sensor, interprets it as a URI, and
     * transforms it to be published at the {@code target} sensor. Transformation involves replacing
     * the host:port in the URI with that returned by {@link PortForwardManager#lookup(brooklyn.location.Location, int)}.
     *
     * It listens to the entity indicated in {@code original}, looking up its only {@link MachineLocation}.
     * If there is not exactly one {@link MachineLocation} for the given entity, then it does no
     * transformation.
     *
     * @param original
     * @param target
     * @return
     */
    public EnricherSpec<?> uriTransformingEnricher(
            EntityAndAttribute<String> original,
            AttributeSensor<String> target);

    /**
     * @see #uriTransformingEnricher(EntityAndAttribute, AttributeSensor)
     *
     * Listens to entity that the enricher is associated with (i.e. the original and target sensors will
     * be on the same entity).
     */
    public EnricherSpec<?> uriTransformingEnricher(
            AttributeSensor<String> original,
            AttributeSensor<String> target);

    /**
     * Builds an enricher that takes the given {@code originalPort} sensor port, and transforms it to be
     * published at the {@code target} sensor as hostname:port. Transformation involves replacing
     * the port with that returned by {@link PortForwardManager#lookup(brooklyn.location.Location, int)}.
     *
     * It listens to the entity indicated in {@code originalPort}, looking up its only {@link MachineLocation}.
     * If there is not exactly one {@link MachineLocation} for the given entity, then it does no
     * transformation.
     *
     * @param original
     * @param target
     * @return
     */
    public EnricherSpec<?> hostAndPortTransformingEnricher(
            EntityAndAttribute<Integer> originalPort,
            AttributeSensor<String> target);

    /**
     * @see #hostAndPortTransformingEnricher(EntityAndAttribute, AttributeSensor)
     *
     * Listens to entity that the enricher is associated with (i.e. the original and target sensors will
     * be on the same entity).
     */
    public EnricherSpec<?> hostAndPortTransformingEnricher(AttributeSensor<Integer> originalPort, AttributeSensor<String> target);

    /**
     * Advertises the address of the forwarding-machine at the given entity->attribute.
     *
     * TODO In CloudStack impl, it also created the IP to be used.
     *
     * @param whereToAdvertiseHostname
     */
    public void openPublicIp(EntityAndAttribute<String> whereToAdvertiseHostname);

    /**
     * @see #openFirewallPortRange(EntityAndAttribute, int, int, brooklyn.util.ssh.Protocol, Cidr)
     */
    public void openFirewallPort(EntityAndAttribute<String> publicIp, int port, Protocol protocol, Cidr accessingCidr);

    /**
     * Opens a range of ports in the firewall, so they are publicly accessible.
     * Implementations may open it just for the given cidr, or potentially to everything.
     *
     * @param publicIp
     * @param lowerBoundPort
     * @param upperBoundPort
     * @param protocol
     * @param accessingCidr
     */
    public void openFirewallPortRange(EntityAndAttribute<String> publicIp, int lowerBoundPort, int upperBoundPort, Protocol protocol, Cidr accessingCidr);

    /**
     * Opens a firewall port forwarding rule targeting the {@code portSensor}
     * and advertises the firewall endpoint(public host and port, where port is chosen
     * if not supplied) at {@code whereToAdvertiseEndpoint}.
     */
    public void openPortForwardingAndAdvertise(EntityAndAttribute<Integer> privatePort, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr, EntityAndAttribute<String> whereToAdvertiseEndpoint);

    /*
     * Getters for local state.
     */

    PortForwarder getPortForwarder();
    PortForwarderAsync getPortForwarderAsync();
    PortForwardManager getPortForwardManager();
    JcloudsPortForwarderExtension getPortForwarderExtension();

}
