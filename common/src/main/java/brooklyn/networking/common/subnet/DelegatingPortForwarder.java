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

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;

import com.google.common.base.Optional;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.api.management.ManagementContext;
import org.apache.brooklyn.location.access.PortForwardManager;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.HasNetworkAddresses;
import org.apache.brooklyn.util.net.Protocol;

public class DelegatingPortForwarder implements PortForwarder {

    private PortForwardManager portForwardManager;
    protected ManagementContext managementContext;
    protected Entity owner;
    protected List<Location> locations;
    
    private volatile PortForwarder delegate;

    public DelegatingPortForwarder() {
    }

    public DelegatingPortForwarder(PortForwardManager portForwardManager) {
        this.portForwardManager = portForwardManager;
    }

    public void injectDelegate(PortForwarder delegate) {
        this.delegate = checkNotNull(delegate, "delegate");
        if (managementContext != null) delegate.injectManagementContext(managementContext);
        if (owner != null || locations != null) delegate.inject(owner, locations);
    }
    
    protected PortForwarder getDelegate() {
        if (delegate != null) {
            return delegate;
        } else {
            throw new IllegalStateException("No delegate injected");
        }
    }
    
    @Override
    public void injectManagementContext(ManagementContext managementContext) {
        this.managementContext = managementContext;
        if (delegate != null) delegate.injectManagementContext(managementContext);
    }

    @Override
    public void inject(Entity owner, List<Location> locations) {
        this.owner = owner;
        this.locations = locations;
        if (delegate != null) delegate.inject(owner, locations);
    }

    @Override
    public String openGateway() {
        return getDelegate().openGateway();
    }

    @Override
    public String openStaticNat(Entity serviceToOpen) {
        return getDelegate().openStaticNat(serviceToOpen);
    }

    @Override
    public void openFirewallPort(Entity entity, int port, Protocol protocol, Cidr accessingCidr) {
        getDelegate().openFirewallPort(entity, port, protocol, accessingCidr);
    }

    @Override
    public void openFirewallPortRange(Entity entity, PortRange portRange, Protocol protocol, Cidr accessingCidr) {
        getDelegate().openFirewallPortRange(entity, portRange, protocol, accessingCidr);
    }

    @Override
    public HostAndPort openPortForwarding(HasNetworkAddresses machine, int targetPort, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        return getDelegate().openPortForwarding(machine, targetPort, optionalPublicPort, protocol, accessingCidr);
    }

    @Override
    public HostAndPort openPortForwarding(HostAndPort targetSide, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        return getDelegate().openPortForwarding(targetSide, optionalPublicPort, protocol, accessingCidr);
    }

    @Override
    public boolean closePortForwarding(HostAndPort targetSide, HostAndPort publicSide, Protocol protocol) {
        return getDelegate().closePortForwarding(targetSide, publicSide, protocol);
    }

    @Override
    public boolean closePortForwarding(HasNetworkAddresses machine, int targetPort, HostAndPort publicSide, Protocol protocol) {
        return getDelegate().closePortForwarding(machine, targetPort, publicSide, protocol);
    }

    @Override
    public boolean isClient() {
        return getDelegate().isClient();
    }

    @Override
    public PortForwardManager getPortForwardManager() {
        return (portForwardManager != null) ? portForwardManager : getDelegate().getPortForwardManager();
    }
}
