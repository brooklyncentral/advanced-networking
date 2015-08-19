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
package brooklyn.networking.common.subnet;

import com.google.common.annotations.Beta;
import com.google.common.base.Optional;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Protocol;

/**
 * Utility for setting up network routing (e.g. NAT, port-forwarding, etc).
 * 
 * See {@link PortForwarder}, which gives the building blocks. However, this
 * class is often more useful when building an application as the commands can 
 * be registered during initialization, to be performed as soon as the VMs are
 * accessible etc.
 * 
 * A common use-case is for clouds that have a limited number of public IPs (or 
 * where VMs are not exposed to the public for security reasons), so NAT and 
 * port-forwarding have to be used to provide access. In the {@link AbstractEntity#init()} 
 * or the the {@link Startable#start(java.util.Collection)} method, the network
 * commands can be executed so that when the VMs+entities come up they'll have
 * the correct networking configuration.
 */
@Beta
public interface PortForwarderAsync {

    /**
     * Advertises the IP/hostname of a public gateway (for accessing the private subnet).
     * This may involve asynchronously creating the gateway.
     * <p>
     * The attribute may not have been set when this method returns (e.g. if the subnet is
     * still being created).
     * 
     * @param whereToAdvertiseHostname
     */
    public void openGatewayAsync(EntityAndAttribute<String> whereToAdvertiseHostname);

    /**
     * Gives access to the given entity via static NAT (i.e. setup a publicly visible IP, so that it routes
     * through to the entity's own IP).
     * <p>
     * The entity must have a {@link Attributes#HOSTNAME} attribute.
     * <p>
     * Some cloud-specific implementations will ignore the hostname, and instead lookup the entity's 
     * machine location via {@link Entity#getLocations()} to find the VM, and will use that VM's id.
     * <p>
     * The attribute may not have been set when this method returns (e.g. if the entity's VM is still 
     * starting, or the the subnet is still being created).
     * 
     * @param serviceToOpen
     * @param whereToAdvertiseHostname
     */
    public void openStaticNatAsync(Entity serviceToOpen, EntityAndAttribute<String> whereToAdvertiseHostname);
    
    /**
     * Opens access to the given entity's port.
     * <p>
     * @see #openFirewallPortRangeAsync(EntityAndAttribute, PortRange, Protocol, Cidr)
     */
    public void openFirewallPortAsync(EntityAndAttribute<String> publicIp, int port, Protocol protocol, Cidr accessingCidr);
    
    /**
     * Opens access to the given entity's ports.
     * <p>
     * The entity must have a {@link Attributes#HOSTNAME} attribute.
     * <p>
     * Some cloud-specific implementations will ignore the hostname, and instead lookup the entity's 
     * machine location via {@link Entity#getLocations()} to find the VM, and will use that VM's id.
     * <p>
     * The ports may not have been opened when this method returns (e.g. if the entity's VM is still 
     * starting, or the the subnet is still being created).
     * 
     * @param publicIp
     * @param port
     * @param protocol
     * @param accessingCidr
     */
    public void openFirewallPortRangeAsync(EntityAndAttribute<String> publicIp, PortRange portRange, Protocol protocol, Cidr accessingCidr);
    
    /**
     * Sets up port-forwarding for this entity's given port, via the public gateway.
     * <p>
     * The entity (i.e. {@code privatePort.getEntity()} must have a {@link Attributes#HOSTNAME} attribute.
     * <p>
     * Some cloud-specific implementations will ignore the hostname, and instead lookup the entity's 
     * machine location via {@link Entity#getLocations()} to find the VM, and will use that VM's id.
     * <p>
     * The endpoint will be advertised as a string of the form ip:port (or hostname:port).
     * <p>
     * The port-forwarding may not have been set up when this method returns (e.g. if the entity's VM is still 
     * starting, or the the subnet is still being created).
     * 
     * @param portSensor
     * @param optionalPublicPort
     * @param protocol
     * @param accessingCidr
     * @param whereToAdvertiseEndpoint
     */
    public void openPortForwardingAndAdvertise(EntityAndAttribute<Integer> privatePort, Optional<Integer> optionalPublicPort, 
            Protocol protocol, Cidr accessingCidr, EntityAndAttribute<String> whereToAdvertiseEndpoint);
}
