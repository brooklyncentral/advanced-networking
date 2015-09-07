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
package brooklyn.networking.subnet;

import com.google.common.base.Optional;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.location.Machines;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Protocol;

import brooklyn.networking.common.subnet.PortForwarder;

/** 
 * Kept for persisted state backwards compatibility.
 * 
 * The inner classes are also preserved, along with the naming of anonymous inner classes (which
 * by default will be $1, $2 etc in the order they are declared).
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

    /**
     * This method will never be called. It is purely to contain the inner class for backwards compatibility, 
     * preserving the parameter names etc.
     */
    private void innerClass_openFirewallPortRangeAsync(final EntityAndAttribute<String> publicIp, final PortRange portRange, final Protocol protocol, final Cidr accessingCidr) {
        new Runnable() {
            public void run() {
                portForwarder.openFirewallPortRange(publicIp.getEntity(), portRange, protocol, accessingCidr);
            }};
    }

    /**
     * This method will never be called. It is purely to contain the inner class for backwards compatibility, 
     * preserving the parameter names etc.
     */
    private void innerClass_openPortForwardingAndAdvertise(final EntityAndAttribute<Integer> privatePort, final Optional<Integer> optionalPublicPort,
            final Protocol protocol, final Cidr accessingCidr, final EntityAndAttribute<String> whereToAdvertiseEndpoint) {
        new Runnable() {
            public void run() {
                // implementation removed as it is never used
            }};
    }

    /**
     * Kept for persisted state backwards compatibility.
     * 
     * @deprecated since 0.7.0; use {@link brooklyn.networking.common.subnet.PortForwarderAsyncImpl.DeferredExecutor}
     */
    protected class DeferredExecutor<T> extends brooklyn.networking.common.subnet.PortForwarderAsyncImpl.DeferredExecutor<T> {
        public DeferredExecutor(String description, EntityAndAttribute<T> attribute, Runnable task) {
            super(description, attribute, task);
        }
    }
}
