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

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.Sensor;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.location.Machines;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.TaskBuilder;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;

import brooklyn.networking.AttributeMunger;

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

    // TODO Keep for persisted state, in case class $1 is being referenced.
    //      Can't just rename it to OpenFirewallPortRangeJob, because fields are different.
    @SuppressWarnings("unused")
    @Deprecated
    private void deprecated_openFirewallPortRangeAsync(final EntityAndAttribute<String> publicIp, final PortRange portRange, final Protocol protocol, final Cidr accessingCidr) {
        new Runnable() {
            public void run() {
                portForwarder.openFirewallPortRange(publicIp.getEntity(), portRange, protocol, accessingCidr);
            }
        };
    }
    
    @Override
    public void openFirewallPortRangeAsync(EntityAndAttribute<String> publicIp, PortRange portRange, Protocol protocol, Cidr accessingCidr) {
        Runnable job = new OpenFirewallPortRangeJob(portForwarder, publicIp, portRange, protocol, accessingCidr);
        DeferredExecutor<String> updater = new DeferredExecutor<String>("open-firewall", publicIp, Predicates.notNull(), job);
        subscribe(publicIp.getEntity(), publicIp.getAttribute(), updater);
        updater.apply(publicIp.getEntity(), publicIp.getValue());
    }
    private static class OpenFirewallPortRangeJob implements Runnable {
        private final PortForwarder portForwarder;
        private final EntityAndAttribute<String> publicIp;
        private final PortRange portRange;
        private final Protocol protocol;
        private final Cidr accessingCidr;
        
        OpenFirewallPortRangeJob(PortForwarder portForwarder, final EntityAndAttribute<String> publicIp, final PortRange portRange, final Protocol protocol, final Cidr accessingCidr) {
            this.portForwarder = portForwarder;
            this.publicIp = publicIp;
            this.portRange = portRange;
            this.protocol = protocol;
            this.accessingCidr = accessingCidr;
        }
        public void run() {
            portForwarder.openFirewallPortRange(publicIp.getEntity(), portRange, protocol, accessingCidr);
        }
    }

    // TODO Keep for persisted state, in case class $2 is being referenced.
    //      Can't just rename it to OpenPortForwardingAdvertiser, because outer-class is different?
    @SuppressWarnings("unused")
    @Deprecated
    private void deprecated_openPortForwardingAndAdvertise(final EntityAndAttribute<Integer> source, final Optional<Integer> optionalPublicPort,
            final Protocol protocol, final Cidr accessingCidr) {
        new Advertiser() {
            @Override
            public void advertise(EntityAndAttribute<Integer> source, HostAndPort publicEndpoint) {
                String sourceSensor = source.getAttribute().getName();
                Entity entity = source.getEntity();
                AttributeSensor<String> mappedSensor = Sensors.newStringSensor("mapped." + sourceSensor);
                AttributeSensor<String> mappedEndpointSensor = Sensors.newStringSensor("mapped.endpoint." + sourceSensor);
                AttributeSensor<Integer> mappedPortSensor = Sensors.newIntegerSensor("mapped.portPart." + sourceSensor);
                String endpoint = publicEndpoint.getHost() + ":" + publicEndpoint.getPort();
                entity.sensors().set(mappedSensor, endpoint);
                entity.sensors().set(mappedEndpointSensor, endpoint);
                entity.sensors().set(mappedPortSensor, publicEndpoint.getPort());
            }
        };
    }
    
    @Override
    public void openPortForwardingAndAdvertise(final EntityAndAttribute<Integer> source, final Optional<Integer> optionalPublicPort,
            final Protocol protocol, final Cidr accessingCidr) {
        Advertiser advertiser = new OpenPortForwardingAdvertiser();
        doOpenPortForwardingAndAdvertise(source, optionalPublicPort, protocol, accessingCidr, advertiser);
    }
    private static class OpenPortForwardingAdvertiser implements Advertiser {
        @Override
        public void advertise(EntityAndAttribute<Integer> source, HostAndPort publicEndpoint) {
            String sourceSensor = source.getAttribute().getName();
            Entity entity = source.getEntity();
            AttributeSensor<String> mappedSensor = Sensors.newStringSensor("mapped." + sourceSensor);
            AttributeSensor<String> mappedEndpointSensor = Sensors.newStringSensor("mapped.endpoint." + sourceSensor);
            AttributeSensor<Integer> mappedPortSensor = Sensors.newIntegerSensor("mapped.portPart." + sourceSensor);
            String endpoint = publicEndpoint.getHost() + ":" + publicEndpoint.getPort();
            entity.sensors().set(mappedSensor, endpoint);
            entity.sensors().set(mappedEndpointSensor, endpoint);
            entity.sensors().set(mappedPortSensor, publicEndpoint.getPort());
        }
    }
    
    // TODO Keep for persisted state, in case class $3 is being referenced.
    //      Can't just rename it to OpenPortForwardingExplicitAdvertiser, because outer-class is different?
    @SuppressWarnings("unused")
    @Deprecated
    private void deprecated_openPortForwardingAndAdvertise(final EntityAndAttribute<Integer> source, final Optional<Integer> optionalPublicPort,
            final Protocol protocol, final Cidr accessingCidr, final EntityAndAttribute<String> whereToAdvertiseEndpoint) {
        new Advertiser() {
            @Override
            public void advertise(EntityAndAttribute<Integer> source, HostAndPort publicEndpoint) {
                String endpoint = publicEndpoint.getHost() + ":" + publicEndpoint.getPort();
                whereToAdvertiseEndpoint.setValue(endpoint);
            }
        };
    }
    
    @Override
    public void openPortForwardingAndAdvertise(EntityAndAttribute<Integer> source, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr, EntityAndAttribute<String> whereToAdvertiseEndpoint) {
        Advertiser advertiser = new OpenPortForwardingExplicitAdvertiser(whereToAdvertiseEndpoint);
        doOpenPortForwardingAndAdvertise(source, optionalPublicPort, protocol, accessingCidr, advertiser);
    }
    private static class OpenPortForwardingExplicitAdvertiser implements Advertiser {
        private final EntityAndAttribute<String> whereToAdvertiseEndpoint;
        
        public OpenPortForwardingExplicitAdvertiser(EntityAndAttribute<String> whereToAdvertiseEndpoint) {
            this.whereToAdvertiseEndpoint = whereToAdvertiseEndpoint;
        }
        @Override
        public void advertise(EntityAndAttribute<Integer> source, HostAndPort publicEndpoint) {
            String endpoint = publicEndpoint.getHost() + ":" + publicEndpoint.getPort();
            whereToAdvertiseEndpoint.setValue(endpoint);
        }
    }
    
    private static interface Advertiser {
        public void advertise(final EntityAndAttribute<Integer> source, HostAndPort publicEndpoint);
    }
    
    // TODO Keep for persisted state, in case class $4 is being referenced.
    //      Can't just rename it to OpenPortForwardingExplicitAdvertiser, because outer-class is different?
    @SuppressWarnings("unused")
    @Deprecated
    private void deprecated_doOpenPortForwardingAndAdvertise(final EntityAndAttribute<Integer> source, final Optional<Integer> optionalPublicPort,
            final Protocol protocol, final Cidr accessingCidr, final Advertiser advertiser) {
        new Runnable() {

            private AtomicReference<MachineAndPort> updated = new AtomicReference<>();

            @Override
            public void run() {
                Entity entity = source.getEntity();
                Integer privatePortVal = source.getValue();
                if (privatePortVal == null) {
                    if (log.isDebugEnabled())
                        log.debug("Private port null for entity {}; not opening or advertising mapped port", entity, source.getAttribute().getName());
                    return;
                }
                Maybe<MachineLocation> machineLocationMaybe = Machines.findUniqueMachineLocation(entity.getLocations());
                if (machineLocationMaybe.isAbsent()) {
                    if (log.isDebugEnabled())
                        log.debug("No machine found for entity {}; not opening or advertising mapped port", entity);
                    return;
                }
                MachineLocation machine = machineLocationMaybe.get();
                MachineAndPort machineAndPort = new MachineAndPort(machine, privatePortVal);
                
                // Check if another thread is already opening the port-forwarding.
                // This can happen because the DeferredExecutor submits the task, and does 
                // not block for completion (hence allowing us to open ports in parallel).
                // Given we have multiple subscriptions (for location + port), we can get
                // called multiple times concurrently.
                if (updated.compareAndSet(null, machineAndPort)) {
                    // We got here first; we open the port-forwarding.
                } else if (machineAndPort.equals(updated.get())) {
                    if (log.isDebugEnabled())
                        log.debug("Already created port-mapping for entity {}, at {} -> {}; not opening again", new Object[]{entity, machine, privatePortVal});
                    return;
                } else {
                    // Check again before logging, in case updated was cleared - 
                    // can happen if openPortForwarding returns null.
                    MachineAndPort oldMachineAndPort = updated.get();
                    if (oldMachineAndPort != null) {
                        log.info("Previous port-forwarding for {} used different machine:port ({}:{}, compared to now {}:{}); "
                                + "opening with new machine:port",
                                new Object[] {entity, oldMachineAndPort.machine, oldMachineAndPort.port, machineAndPort.machine, 
                                machineAndPort.port});
                    }
                }

                HostAndPort publicEndpoint;
                try {
                    publicEndpoint = portForwarder.openPortForwarding(machine, privatePortVal, optionalPublicPort, protocol, accessingCidr);
                } catch (Throwable t) {
                    updated.set(null);
                    throw Exceptions.propagate(t);
                }
                if (publicEndpoint == null) {
                    // Failed to open port-forwarding; clear "updated" so another thread can have a go
                    // if we are ever called again.
                    log.warn("No host:port obtained for " + machine + " -> " + privatePortVal + "; not advertising mapped port");
                    updated.set(null);
                    return;
                }

                // TODO What publicIpId to use in portForwardManager.associate? Elsewhere, uses jcloudsMachine.getJcloudsId().
                portForwarder.getPortForwardManager().associate(machine.getId(), publicEndpoint, machine, privatePortVal);
                
                advertiser.advertise(source, publicEndpoint);
                log.debug("Set target sensor, advertising mapping of {}->{} ({})", new Object[] {entity, source.getAttribute().getName(), publicEndpoint});
            }
        };
    }

    protected void doOpenPortForwardingAndAdvertise(EntityAndAttribute<Integer> source, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr, Advertiser advertiser) {
        Runnable job = new OpenPortForwardingAndAdvertiseJob(portForwarder, source, optionalPublicPort, protocol, 
                accessingCidr, advertiser);
        DeferredExecutor<Integer> updater = new DeferredExecutor<>("open-port-forwarding", source, Predicates.notNull(), 
                Boolean.FALSE, job);
        
        subscribe(ImmutableMap.of("notifyOfInitialValue", Boolean.TRUE), source.getEntity(), source.getAttribute(), updater);
        subscribe(source.getEntity(), AbstractEntity.LOCATION_ADDED, updater);
    }
    private static class OpenPortForwardingAndAdvertiseJob implements Runnable {
        private final PortForwarder portForwarder;
        final EntityAndAttribute<Integer> source;
        final Optional<Integer> optionalPublicPort;
        final Protocol protocol;
        final Cidr accessingCidr;
        final Advertiser advertiser;
        private final AtomicReference<MachineAndPort> updated = new AtomicReference<>();

        OpenPortForwardingAndAdvertiseJob(PortForwarder portForwarder, final EntityAndAttribute<Integer> source, 
                        Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr, Advertiser advertiser) {
            this.portForwarder = portForwarder;
            this.source = source;
            this.optionalPublicPort = optionalPublicPort;
            this.protocol = protocol;
            this.accessingCidr = accessingCidr;
            this.advertiser = advertiser;
        }

        @Override
        public void run() {
            Entity entity = source.getEntity();
            Integer privatePortVal = source.getValue();
            if (privatePortVal == null) {
                if (log.isDebugEnabled())
                    log.debug("Private port null for entity {}; not opening or advertising mapped port", entity, source.getAttribute().getName());
                return;
            }
            Maybe<MachineLocation> machineLocationMaybe = Machines.findUniqueMachineLocation(entity.getLocations());
            if (machineLocationMaybe.isAbsent()) {
                if (log.isDebugEnabled())
                    log.debug("No machine found for entity {}; not opening or advertising mapped port", entity);
                return;
            }
            MachineLocation machine = machineLocationMaybe.get();
            MachineAndPort machineAndPort = new MachineAndPort(machine, privatePortVal);
            
            // Check if another thread is already opening the port-forwarding.
            // This can happen because the DeferredExecutor submits the task, and does 
            // not block for completion (hence allowing us to open ports in parallel).
            // Given we have multiple subscriptions (for location + port), we can get
            // called multiple times concurrently.
            if (updated.compareAndSet(null, machineAndPort)) {
                // We got here first; we open the port-forwarding.
            } else if (machineAndPort.equals(updated.get())) {
                if (log.isDebugEnabled())
                    log.debug("Already created port-mapping for entity {}, at {} -> {}; not opening again", new Object[]{entity, machine, privatePortVal});
                return;
            } else {
                // Check again before logging, in case updated was cleared - 
                // can happen if openPortForwarding returns null.
                MachineAndPort oldMachineAndPort = updated.get();
                if (oldMachineAndPort != null) {
                    log.info("Previous port-forwarding for {} used different machine:port ({}:{}, compared to now {}:{}); "
                            + "opening with new machine:port",
                            new Object[] {entity, oldMachineAndPort.machine, oldMachineAndPort.port, machineAndPort.machine, 
                            machineAndPort.port});
                }
            }

            HostAndPort publicEndpoint;
            try {
                publicEndpoint = portForwarder.openPortForwarding(machine, privatePortVal, optionalPublicPort, protocol, accessingCidr);
            } catch (Throwable t) {
                updated.set(null);
                throw Exceptions.propagate(t);
            }
            if (publicEndpoint == null) {
                // Failed to open port-forwarding; clear "updated" so another thread can have a go
                // if we are ever called again.
                log.warn("No host:port obtained for " + machine + " -> " + privatePortVal + "; not advertising mapped port");
                updated.set(null);
                return;
            }

            // TODO What publicIpId to use in portForwardManager.associate? Elsewhere, uses jcloudsMachine.getJcloudsId().
            portForwarder.getPortForwardManager().associate(machine.getId(), publicEndpoint, machine, privatePortVal);
            
            advertiser.advertise(source, publicEndpoint);
            log.debug("Set target sensor, advertising mapping of {}->{} ({})", new Object[] {entity, source.getAttribute().getName(), publicEndpoint});
        }
    }
    
    protected <T> void subscribe(Entity entity, Sensor<T> attribute, SensorEventListener<? super T> listener) {
        adjunctEntity.subscriptions().subscribe(entity, attribute, listener);
    }

    protected <T> void subscribe(Map<String, ?> flags, Entity entity, Sensor<T> attribute, SensorEventListener<? super T> listener) {
        adjunctEntity.subscriptions().subscribe(flags, entity, attribute, listener);
    }

    protected class DeferredExecutor<T> implements SensorEventListener<Object> {
        private final EntityAndAttribute<T> attribute;
        private final Predicate<? super T> readiness;
        private final Runnable runnable;
        private final String description;
        private final Boolean blockUntilEnded;

        public DeferredExecutor(final String description, final EntityAndAttribute<T> attribute, final Runnable runnable) {
            this(description, attribute, Predicates.notNull(), runnable);
        }

        public DeferredExecutor(final String description, final EntityAndAttribute<T> attribute,
                                final Predicate<? super T> readiness, final Runnable runnable) {
            this(description, attribute, readiness, Boolean.TRUE, runnable);
        }

        public DeferredExecutor(final String description, final EntityAndAttribute<T> attribute,
                                final Predicate<? super T> readiness, final Boolean blockUntilEnded, final Runnable runnable) {
            this.description = description;
            this.attribute = attribute;
            this.readiness = readiness;
            this.runnable = runnable;
            this.blockUntilEnded = blockUntilEnded;
        }

        @Override
        public void onEvent(SensorEvent<Object> event) {
            apply(event.getSource(), event.getValue());
        }

        public void apply(final Entity source, final Object valueIgnored) {
            T val = (T) attribute.getValue();
            if (!readiness.apply(val)) {
                log.warn("Skipping {} for {} because attribute {} not ready", new Object[]{description, attribute.getEntity(), attribute.getAttribute()});
                return;
            }
            final Task<Void> task = TaskBuilder.<Void>builder().displayName(description).body(runnable).build();
            DynamicTasks.queueIfPossible(task).orSubmitAsync(source).asTask();
            if (blockUntilEnded) {
                final String originalBlock = Tasks.setBlockingDetails(description);
                try {
                    task.blockUntilEnded();
                } finally {
                    Tasks.setBlockingDetails(originalBlock);
                }
            }
        }
    }
    
    private static class MachineAndPort {
        private final MachineLocation machine;
        private final int port;

        MachineAndPort(MachineLocation machine, int port) {
            this.machine = checkNotNull(machine, "machine");
            this.port = port;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MachineAndPort)) return false;
            MachineAndPort o = (MachineAndPort) obj;
            return machine.equals(o.machine) && port == o.port;
        }
        
        @Override
        public int hashCode() {
            return Objects.hashCode(machine, port);
        }
        
        @Override
        public String toString() {
            return machine+" -> "+port;
        }
    }
}
