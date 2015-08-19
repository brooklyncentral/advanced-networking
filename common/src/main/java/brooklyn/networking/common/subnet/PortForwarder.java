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

import java.util.List;

import com.google.common.annotations.Beta;
import com.google.common.base.Optional;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.core.mgmt.ManagementContextInjectable;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.HasNetworkAddresses;
import org.apache.brooklyn.util.net.Protocol;

/**
 * Utility for setting up network routing (e.g. NAT, port-forwarding, etc).
 * 
 * All operations are blocking, and assume that the environment is in a state ready for these
 * operations to be performed (e.g. that the given entity's VM is started, etc).
 * 
 * See {@link PortForwarderAsync}, which is more useful when building an application as the 
 * commands can be registered during initialization, to be performed as soon as the VMs are
 * accessible etc.
 */
@Beta
public interface PortForwarder extends ManagementContextInjectable {

    public void inject(Entity owner, List<Location> locations);

    /**
     * Returns the IP/hostname of a public gateway (for accessing the private subnet).
     * This may involve asynchronously creating the gateway.
     * <p>
     * 
     * @throw IllegalStateException if not ready to open the gateway (e.g. if the subnet is 
     *                              still being created)
     */
    public String openGateway();

    /**
     * Gives access to the given entity via static NAT (i.e. setup a publicly visible IP, so that it routes
     * through to the entity's own IP).
     * <p>
     * The entity must have a {@link Attributes#HOSTNAME} attribute.
     * <p>
     * Some cloud-specific implementations will ignore the hostname, and instead lookup the entity's 
     * machine location via {@link Entity#getLocations()} to find the VM, and will use that VM's id.
     * <p>
     * 
     * @param serviceToOpen
     * 
     * @throw IllegalStateException if not ready, e.g. if the entity's VM is still starting
     */
    public String openStaticNat(Entity serviceToOpen);
    
    /**
     * Opens access to the given entity's port.
     * <p>
     * @see #openFirewallPortRange(EntityAndAttribute, PortRange, Protocol, Cidr)
     */
    public void openFirewallPort(Entity entity, int port, Protocol protocol, Cidr accessingCidr);
    
    /**
     * Opens access to the given entity's ports.
     * <p>
     * The entity must have a {@link Attributes#HOSTNAME} attribute.
     * <p>
     * Some cloud-specific implementations will ignore the hostname, and instead lookup the entity's 
     * machine location via {@link Entity#getLocations()} to find the VM, and will use that VM's id.
     * <p>
     * 
     * @param entity
     * @param port
     * @param protocol
     * @param accessingCidr
     * 
     * @throw IllegalStateException if not ready, e.g. if the entity's VM is still starting
     */
    public void openFirewallPortRange(Entity entity, PortRange portRange, Protocol protocol, Cidr accessingCidr);
    
    /**
     * Sets up port-forwarding for this machine's given port, via the public gateway.
     * 
     * @param machine
     * @param targetPort
     * @param optionalPublicPort
     * @param protocol
     * @param accessingCidr
     */
    public HostAndPort openPortForwarding(HasNetworkAddresses machine, int targetPort,
            Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr);

    /**
     * Sets up port-forwarding to the given host:port
     * 
     * @param targetSide
     * @param optionalPublicPort
     * @param protocol
     * @param accessingCidr
     * @return
     */
    public HostAndPort openPortForwarding(HostAndPort targetSide, Optional<Integer> optionalPublicPort, 
            Protocol protocol, Cidr accessingCidr);

    /**
     * Tears down the port-forwarding for this machine's given port, via the public gateway.
     */
    public boolean closePortForwarding(HostAndPort targetSide, HostAndPort publicSide, Protocol protocol);

    /**
     * Tears down the port-forwarding for this machine's given port, via the public gateway.
     */
    public boolean closePortForwarding(HasNetworkAddresses machine, int targetPort, HostAndPort publicSide, Protocol protocol);

    /** true if the underlying instance is a client pointing at an authority whose persistence is managed elsewhere */ 
    public boolean isClient();

    /** if the PortForwarder stores a {@link PortForwardManager} it can return it, otherwise return null */
    public PortForwardManager getPortForwardManager();
    
}
