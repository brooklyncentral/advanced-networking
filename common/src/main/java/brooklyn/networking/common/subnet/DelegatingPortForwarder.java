package brooklyn.networking.common.subnet;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;

import brooklyn.entity.Entity;
import brooklyn.location.Location;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.management.ManagementContext;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.HasNetworkAddresses;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.net.HostAndPort;

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
