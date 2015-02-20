package brooklyn.networking.subnet;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.location.MachineLocation;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.basic.Machines;
import brooklyn.networking.common.subnet.PortForwarder;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.net.HostAndPort;

/** 
 * Kept for persisted state backwards compatibility
 * 
 * @deprecated since 0.7.0; use {@link brooklyn.networking.common.subnet.PortForwarderAsyncImpl}
 */
@Deprecated
public class PortForwarderAsyncImpl extends brooklyn.networking.common.subnet.PortForwarderAsyncImpl {

    private final PortForwarder portForwarder;

    public PortForwarderAsyncImpl(EntityLocal adjunctEntity,
            PortForwarder portForwarder, PortForwardManager portForwardManager) {
        super(adjunctEntity, portForwarder, portForwardManager);
        this.portForwarder = portForwarder;
    }

    private void innerClass_openFirewallPortRangeAsync(final EntityAndAttribute<String> publicIp, final PortRange portRange, final Protocol protocol, final Cidr accessingCidr) {
        new Runnable() {
            public void run() {
                portForwarder.openFirewallPortRange(publicIp.getEntity(), portRange, protocol, accessingCidr);
            }};
    }

    private void innerClass_openPortForwardingAndAdvertise(final EntityAndAttribute<Integer> privatePort, final Optional<Integer> optionalPublicPort,
            final Protocol protocol, final Cidr accessingCidr, final EntityAndAttribute<String> whereToAdvertiseEndpoint) {
        new Runnable() {
            public void run() {
                Entity entity = privatePort.getEntity();
                Integer privatePortVal = privatePort.getValue();
                MachineLocation machine = Machines.findUniqueMachineLocation(entity.getLocations()).get();
                HostAndPort publicEndpoint = portForwarder.openPortForwarding(machine, privatePortVal, optionalPublicPort, protocol, accessingCidr);
                
                // TODO What publicIpId to use in portForwardManager.associate? Elsewhere, uses jcloudsMachine.getJcloudsId().
                portForwarder.getPortForwardManager().associate(machine.getId(), publicEndpoint, machine, privatePortVal);
                whereToAdvertiseEndpoint.setValue(publicEndpoint.getHostText()+":"+publicEndpoint.getPort());
            }};
    }

    /**
     * @deprecated since 0.7.0; use {@link brooklyn.networking.common.subnet.PortForwarderAsyncImpl.DeferredExecutor}
     */
    protected class DeferredExecutor<T> extends brooklyn.networking.common.subnet.PortForwarderAsyncImpl.DeferredExecutor<T> {
        public DeferredExecutor(String description, EntityAndAttribute<T> attribute, Runnable task) {
            super(description, attribute, task);
        }
    }
}
