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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.event.AttributeSensor;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.location.MachineLocation;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.basic.Machines;
import brooklyn.location.basic.PortRanges;
import brooklyn.networking.AttributeMunger;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.net.HostAndPort;

public class PortForwarderAsyncImpl implements PortForwarderAsync {

    private static final Logger log = LoggerFactory.getLogger(PortForwarderAsyncImpl.class);

    private final EntityLocal adjunctEntity;
    private final PortForwarder portForwarder;

    public PortForwarderAsyncImpl(EntityLocal adjunctEntity, PortForwarder portForwarder, PortForwardManager portForwardManager) {
        this.adjunctEntity = adjunctEntity;
        this.portForwarder = portForwarder;
    }

    @Override
    public void openGatewayAsync(EntityAndAttribute<String> whereToAdvertiseHostname) {
        // IP of port-forwarder already exists; can call synchronously
        String gateway = portForwarder.openGateway();
        AttributeMunger.setAttributeIfChanged(whereToAdvertiseHostname, gateway);
    }

    @Override
    public void openStaticNatAsync(Entity serviceToOpen, EntityAndAttribute<String> whereToAdvertiseHostname) {
        // FIXME Do in deferred block; what do we wait for?
        String staticNat = portForwarder.openStaticNat(serviceToOpen);
        whereToAdvertiseHostname.setValue(staticNat);
    }

    @Override
    public void openFirewallPortAsync(EntityAndAttribute<String> publicIp, int port, Protocol protocol, Cidr accessingCidr) {
        openFirewallPortRangeAsync(publicIp, PortRanges.fromInteger(port), protocol, accessingCidr);
    }

    @Override
    public void openFirewallPortRangeAsync(final EntityAndAttribute<String> publicIp, final PortRange portRange, final Protocol protocol, final Cidr accessingCidr) {
        DeferredExecutor<String> updater = new DeferredExecutor<String>("open-firewall", publicIp, Predicates.notNull(), new Runnable() {
            public void run() {
                portForwarder.openFirewallPortRange(publicIp.getEntity(), portRange, protocol, accessingCidr);
            }});
        subscribe(publicIp.getEntity(), publicIp.getAttribute(), updater);
        updater.apply(publicIp.getEntity(), publicIp.getValue());
    }

    @Override
    public void openPortForwardingAndAdvertise(final EntityAndAttribute<Integer> privatePort, final Optional<Integer> optionalPublicPort,
            final Protocol protocol, final Cidr accessingCidr, final EntityAndAttribute<String> whereToAdvertiseEndpoint) {
        DeferredExecutor<Integer> updater = new DeferredExecutor<Integer>("open-port-forwarding", privatePort, Predicates.notNull(), new Runnable() {
            public void run() {
                Entity entity = privatePort.getEntity();
                Integer privatePortVal = privatePort.getValue();
                MachineLocation machine = Machines.findUniqueMachineLocation(entity.getLocations()).get();
                HostAndPort publicEndpoint = portForwarder.openPortForwarding(machine, privatePortVal, optionalPublicPort, protocol, accessingCidr);
                
                // TODO What publicIpId to use in portForwardManager.associate? Elsewhere, uses jcloudsMachine.getJcloudsId().
                portForwarder.getPortForwardManager().associate(machine.getId(), publicEndpoint, machine, privatePortVal);
                whereToAdvertiseEndpoint.setValue(publicEndpoint.getHostText()+":"+publicEndpoint.getPort());
            }});
        subscribe(privatePort.getEntity(), privatePort.getAttribute(), updater);
        updater.apply(privatePort.getEntity(), privatePort.getValue());
    }

    protected <T> void subscribe(Entity entity, AttributeSensor<T> attribute, SensorEventListener<? super T> listener) {
        adjunctEntity.subscribe(entity, attribute, listener);
    }

    protected class DeferredExecutor<T> implements SensorEventListener<Object> {
        private final EntityAndAttribute<T> attribute;
        private final Predicate<? super T> readiness;
        private final Runnable task;
        private final String description;

        public DeferredExecutor(String description, EntityAndAttribute<T> attribute, Runnable task) {
            this(description, attribute, Predicates.notNull(), task);
        }

        public DeferredExecutor(String description, EntityAndAttribute<T> attribute, Predicate<? super T> readiness, Runnable task) {
            this.description = description;
            this.attribute = attribute;
            this.readiness = readiness;
            this.task = task;
        }

        @Override
        public void onEvent(SensorEvent<Object> event) {
            apply(event.getSource(), event.getValue());
        }

        public void apply(Entity source, Object valueIgnored) {
            T val = (T) attribute.getValue();
            if (!readiness.apply(val)) {
                log.warn("Skipping {} for {} because attribute {} not ready", new Object[] {description, attribute.getEntity(), attribute.getAttribute()});
                return;
            }

            task.run();
        }
    }
}
