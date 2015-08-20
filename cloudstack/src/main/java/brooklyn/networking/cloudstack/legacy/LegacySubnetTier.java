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
package brooklyn.networking.cloudstack.legacy;

import java.util.Map;

import org.jclouds.cloudstack.domain.FirewallRule;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.BasicConfigKey;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.access.BrooklynAccessUtils;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;
import org.apache.brooklyn.util.net.Cidr;

@ImplementedBy(LegacySubnetTierImpl.class)
public interface LegacySubnetTier extends Entity, Startable {

    public static final ConfigKey<Boolean> USE_SUBNET = new BasicConfigKey<Boolean>(Boolean.class, 
            "bt.topology.useSubnet", "Whether to use a subnet (implied if using VPC)", false);

    public static final ConfigKey<Cidr> SUBNET_CIDR = new BasicConfigKey<Cidr>(Cidr.class, 
            "subnet.cidr", "CIDR to use for this subnet", null);

    public static final ConfigKey<Boolean> SUBNET_IS_LOAD_BALANCER = new BasicConfigKey<Boolean>(Boolean.class, 
            "subnet.isLoadBalancer", "whether this subnet should be enabled for LB'ing", false);

    public static final ConfigKey<String> SERVICE_NETWORK_ID = new BasicConfigKey<String>(String.class, "serviceNetwork.id", "Optional ID of a network to use for all internal communications");
    
    public static final AttributeSensor<String> NETWORK_ID = new BasicAttributeSensor<String>(String.class, "bt.subnet.network.id");
    
    public static final AttributeSensor<String> SUBNET_HOSTNAME_SENSOR = new BasicAttributeSensor<String>(String.class, "host.name.subnet", "Subnet-accessible IP/Hostname (for VM's in a subnet)");
    
    public static final ConfigKey<PortForwardManager> PORT_FORWARDING_MANAGER = BrooklynAccessUtils.PORT_FORWARDING_MANAGER;
    
    public static final AttributeSensor<String> PUBLIC_HOSTNAME = new BasicAttributeSensor<String>(String.class, "host.service.default.hostname", 
            "Publicly addressible hostname or IP used when port forwarding to this subnet (forwarding to internal elements)");
    public static final AttributeSensor<String> PUBLIC_HOSTNAME_IP_ADDRESS_ID = new BasicAttributeSensor<String>(String.class, "host.service.default.hostname.ipAddressId", 
            "Internal identifier for the public hostname");
    public static final AttributeSensor<String> DEFAULT_PUBLIC_HOSTNAME_AND_PORT = new BasicAttributeSensor<String>(String.class, "host.service.default.hostnameAndPort", 
            "Provides a publicly accessible hostname:port combo for a service");
    
    // TODO This isn't really a sensor on SubnetTier; we just need to define it somewhere so other things 
    // in the subnet can reference/set it on the entities within the subnet. Where is best place to define 
    // this? Should we create a constants interface?
    public static final AttributeSensor<String> PRIVATE_HOSTNAME = new BasicAttributeSensor<String>(String.class, "hostname.private", 
            "A private hostname or IP within the subnet (used so don't lose private address when transforming hostname etc on an entity)");
    
    public static final AttributeSensor<Map<String,String>> PUBLIC_HOSTNAME_IP_IDS = new BasicAttributeSensor(Map.class, "subnet.publicips.mapping", 
            "Provides a mapping from ip ID to actual public IP");
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static final AttributeSensor<Map<String,Cidr>> SUBNET_ACL =
            (AttributeSensor<Map<String,Cidr>>) (AttributeSensor) // TypeToken would fix need for cast but is just as ugly
            new BasicAttributeSensor<Map/*<String,Cidr>*/>(Map.class, "subnet.firewall.acl.entitiesToCidrs");

    public static final AttributeSensor<PortForwardManager> SUBNET_SERVICE_PORT_FORWARDS =
            new BasicAttributeSensor<PortForwardManager>(PortForwardManager.class, "subnet.port.mappings");

    /** replaces default hostname/address with the subnet hostname 
     * (to ensure internal network interfaces are being used) -- 
     * ports ignored so left unchanged 
     * <p>
     * e.g. DB advertises 10.255.96.80:3306 which is a shared network address;
     * this maps e.g.  mysql://10.255.96.80:3306/  to 
     * mysql://10.96.2.1:3306/   which is a private subnet address,
     * and that value can be emitted on a sensor with the same name 
     * (could be extended to allow other sensor names)
     * higher up in the stack (typically at the subnet level, where subnet is e.g. the data tier) */
    public void propagateSensorStringReplacingWithSubnetAddress(final Entity target, final AttributeSensor<String> sensor);

    /** replaces default hostname/address and optional port with serviceHost 
     * (and optional port as part of that string),
     * to map a service to _outside_ a VPC
     * <p>
     * e.g. DB advertises 10.255.96.80:3306 which is a shared network address;
     * this maps e.g.  mysql://10.255.96.80:3306/  to 
     * mysql://216.65.40.30:53306/   which is a public address with a port forwarding rule,
     * and that value can be emitted on a sensor with the same name (could be extended to allow other sensor names)
     * higher up in the stack (typically at the VPC level, where VPC owns the subnet)
     * <p>
     * NB: target should have a hostname 
     * @param primaryWebCluster  */
    public void transformSensorStringReplacingWithPublicAddressAndPort(
            Entity sensorToMapFromSource, AttributeSensor<String> sensorToMapFromPropagating, 
            Entity optionalSensorOfPortToMapFromSource, final AttributeSensor<Integer> optionalSensorOfPortToMapFrom,
            Entity serviceHostPortToMapToEntity, final AttributeSensor<String> sensorForServiceHostPortToMapTo);

    /** sets up ACL so that this tier is made entirely visible from the indicated tier,
     * when the other tier comes online */
    public void makeVisibleTo(LegacySubnetTier otherTier);

    /** opens a firewall port forwarding rule targeting the serviceToForward (on the servicePortSensor),
     * and advertises the firewall endpoint(host and port, where host is taken from VPC, and port is chosen
     * if not supplied) as the other given sensor on the other given entity
     * <p>
     * the serviceToForward must have a Location with VM_IDENTIFIER set as configuration */
    public void openFirewallPortAndAssign(Entity serviceToForward, AttributeSensor<?> servicePortSensor, Integer optionalPublicPort, Cidr accessingCidr,
            Entity whereToAdvertisePublicServiceEndpoint, AttributeSensor<String> sensorAdvertisingHostnameAndPort);

    public void openStaticNat(Entity serviceToOpen, AttributeSensor<String> sensorAdvertisingPublicHostname);

    public void openPublicIp(EntityAndAttribute<String> whereToAdvertiseHostname);

    public void openFirewallPort(EntityAndAttribute<String> publicIp, int port, FirewallRule.Protocol protocol, Cidr accessingCidr);
    
    public void openFirewallPortRange(EntityAndAttribute<String> publicIp, int lowerBoundPort, int upperBoundPort, FirewallRule.Protocol protocol, Cidr accessingCidr);
    
    public void openFirewallPortAndAdvertise(EntityAndAttribute<String> publicIp, EntityAndAttribute<?> portSensor, Integer optionalPublicPort, FirewallRule.Protocol protocol, Cidr accessingCidr, EntityAndAttribute<String> whereToAdvertiseEndpoint);
    
}
