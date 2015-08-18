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

import static com.google.common.base.Preconditions.checkArgument;

import java.net.URI;
import java.net.URISyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.basic.EntityLocal;
import org.apache.brooklyn.api.event.AttributeSensor;
import org.apache.brooklyn.api.event.SensorEvent;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.policy.EnricherSpec;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.util.flags.SetFromFlag;
import org.apache.brooklyn.location.access.PortForwardManager;
import org.apache.brooklyn.location.access.PortForwardManager.AssociationMetadata;
import org.apache.brooklyn.location.basic.Machines;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;

import brooklyn.enricher.basic.Transformer;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.event.basic.BasicSensorEvent;

public class SubnetEnrichers {

    private static final Logger log = LoggerFactory.getLogger(SubnetEnrichers.class);

    public static EnricherSpec<?> uriTransformingEnricher(SubnetTier subnetTier, AttributeSensor<String> original, AttributeSensor<String> target) {
        return EnricherSpec.create(UriTransformingEnricher.class)
                .configure(UriTransformingEnricher.SUBNET_TIER, subnetTier)
                .configure(UriTransformingEnricher.SOURCE_SENSOR, original)
                .configure(UriTransformingEnricher.TARGET_SENSOR, target);
    }

    public static EnricherSpec<?> uriTransformingEnricher(SubnetTier subnetTier, EntityAndAttribute<String> original, AttributeSensor<String> target) {
        return EnricherSpec.create(UriTransformingEnricher.class)
                .configure(UriTransformingEnricher.SUBNET_TIER, subnetTier)
                .configure(UriTransformingEnricher.SOURCE_SENSOR, original.getAttribute())
                .configure(UriTransformingEnricher.PRODUCER, original.getEntity())
                .configure(UriTransformingEnricher.TARGET_SENSOR, target);
    }

    public static EnricherSpec<?> hostAndPortTransformingEnricher(SubnetTier subnetTier, AttributeSensor<Integer> originalPort, AttributeSensor<String> target) {
        return EnricherSpec.create(HostAndPortTransformingEnricher.class)
                .configure(HostAndPortTransformingEnricher.SUBNET_TIER, subnetTier)
                .configure(HostAndPortTransformingEnricher.SOURCE_SENSOR, originalPort)
                .configure(HostAndPortTransformingEnricher.TARGET_SENSOR, target);
    }

    public static EnricherSpec<?> hostAndPortTransformingEnricher(SubnetTier subnetTier, EntityAndAttribute<Integer> originalPort, AttributeSensor<String> target) {
        return EnricherSpec.create(HostAndPortTransformingEnricher.class)
                .configure(HostAndPortTransformingEnricher.SUBNET_TIER, subnetTier)
                .configure(HostAndPortTransformingEnricher.SOURCE_SENSOR, originalPort.getAttribute())
                .configure(HostAndPortTransformingEnricher.PRODUCER, originalPort.getEntity())
                .configure(HostAndPortTransformingEnricher.TARGET_SENSOR, target);
    }

    public static abstract class AbstractNatTransformingEnricher<T,U> extends Transformer<T,U> {

        // TODO Would like to just use SUBNET_TIER ConfigKey here, but for sub-types the persisted state 
        // may use the old name (enricher.uriTransformer.subnetTier)!
        
        @SetFromFlag("subnetTier")
        public static final ConfigKey<SubnetTier> SUBNET_TIER = ConfigKeys.newConfigKey(SubnetTier.class, "enricher.transformer.subnetTier");

        private PortForwardManager.AssociationListener listener;
        
        protected abstract Integer extractPrivatePort(T sensorVal);
        
        /**
         * For overriding by sub-types for backwards compatibility of persisted state, where the config key may have been different.
         */
        protected SubnetTier getSubnetTier() {
            return getConfig(SUBNET_TIER);
        }
        
        @Override
        public void setEntity(EntityLocal entity) {
            super.setEntity(entity);
            checkArgument(getConfig(SOURCE_SENSOR) instanceof AttributeSensor, "expected SOURCE_SENSOR to be AttributeSensor, found %s", getConfig(SOURCE_SENSOR));
            
            listener = new PortForwardManager.AssociationListener() {
                @Override
                public void onAssociationCreated(AssociationMetadata metadata) {
                    Maybe<MachineLocation> machine = Machines.findUniqueMachineLocation(producer.getLocations());
                    T sensorVal = producer.getAttribute((AttributeSensor<T>)sourceSensor);
                    if (machine.isPresent() && sensorVal != null) {
                        Integer port = extractPrivatePort(sensorVal);
                        if (port != null && port != -1) {
                            if (machine.get().equals(metadata.getLocation()) && metadata.getPrivatePort() == port) {
                                log.debug("Simulating sensor-event on new port-association {}, to trigger transformation by {}", new Object[] {metadata, AbstractNatTransformingEnricher.this});
                                AbstractNatTransformingEnricher.this.onEvent(new BasicSensorEvent<T>(sourceSensor, producer, sensorVal));
                            }
                        }
                    }
                }
                @Override
                public void onAssociationDeleted(AssociationMetadata metadata) {
                    // no-op
                }
            };
            getConfig(SUBNET_TIER).getPortForwardManager().addAssociationListener(listener, Predicates.alwaysTrue());
        }
        
        @Override
        public void destroy() {
            try {
                SubnetTier subnetTier = getConfig(SUBNET_TIER);
                if (listener != null && subnetTier != null && subnetTier.getPortForwardManager() != null) {
                    subnetTier.getPortForwardManager().removeAssociationListener(listener);
                }
            } finally {
                super.destroy();
            }
        }
    }


    public static class UriTransformingEnricher extends AbstractNatTransformingEnricher<Object, String> {

        public static final ConfigKey<SubnetTier> DEPRECATED_SUBNET_TIER = ConfigKeys.newConfigKey(SubnetTier.class, "enricher.uriTransformer.subnetTier");

        @Override
        protected SubnetTier getSubnetTier() {
            SubnetTier result = super.getSubnetTier();
            if (result == null) {
                result = getConfig(DEPRECATED_SUBNET_TIER);
            }
            return result;
        }
        
        @Override
        protected Integer extractPrivatePort(Object sensorVal) {
            URI uri = URI.create(sensorVal.toString());
            return uri.getPort();
        }
        
        @Override
        public void init() {
            SubnetTier subnetTier = getConfig(SUBNET_TIER);
            config().set(TRANSFORMATION_FROM_EVENT, new UriTransformingFunction(subnetTier));
            config().set(SUPPRESS_DUPLICATES, true);
        }
    }

    private static class UriTransformingFunction implements Function<SensorEvent<Object>,String> {

        private final SubnetTier subnetTier;
        
        public UriTransformingFunction(SubnetTier subnetTier) {
            this.subnetTier = Preconditions.checkNotNull(subnetTier, "subnetTier");
        }

        @Override
        public String apply(SensorEvent<Object> event) {
            String sensorVal = Strings.toString(event.getValue());
            Entity source = event.getSource();
            Maybe<MachineLocation> machine = Machines.findUniqueMachineLocation(source.getLocations());
            if (sensorVal != null && machine.isPresent()) {
                URI uri = URI.create(sensorVal);
                int port = uri.getPort();
                if (port != -1) {
                    HostAndPort publicTarget = subnetTier.getPortForwardManager().lookup(machine.get(), port);
                    if (publicTarget == null) {
                        // TODO What if publicTarget is still null, but will be set soon? We're not subscribed to changes in the PortForwardManager!
                        // TODO Should we return null or sensorVal? In this method we always return sensorVal;
                        //      but in HostAndPortTransformingEnricher we always return null!
                        log.debug("sensor mapper not transforming {} URI {}, because no port-mapping for {}", new Object[] {source, sensorVal, machine.get()});
                        return sensorVal;
                    }
                    URI result;
                    try {
                        result = new URI(uri.getScheme(), uri.getUserInfo(), publicTarget.getHostText(), publicTarget.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
                    } catch (URISyntaxException e) {
                        log.debug("Error transforming URI "+uri+", using target "+publicTarget+"; rethrowing");
                        throw Exceptions.propagate(e);
                    }
                    log.debug("sensor mapper transforming URI "+uri+" to "+result+"; target="+publicTarget);
                    return result.toString();
                } else {
                    log.debug("sensor mapper not transforming URI "+uri+" because defines no port");
                    return sensorVal;
                }
            } else {
                return sensorVal;
            }
        }
    }

    public static class HostAndPortTransformingEnricher extends AbstractNatTransformingEnricher<Integer, String> {

        public static final ConfigKey<SubnetTier> DEPRECATED_SUBNET_TIER = ConfigKeys.newConfigKey(SubnetTier.class, "enricher.uriTransformer.subnetTier");

        @Override
        protected SubnetTier getSubnetTier() {
            SubnetTier result = super.getSubnetTier();
            if (result == null) {
                result = getConfig(DEPRECATED_SUBNET_TIER);
            }
            return result;
        }
        
        @Override
        protected Integer extractPrivatePort(Integer sensorVal) {
            return sensorVal;
        }

        public void init() {
            SubnetTier subnetTier = getConfig(SUBNET_TIER);
            config().set(TRANSFORMATION_FROM_EVENT, new HostAndPortTransformingFunction(subnetTier));
        }
    }

    public static final class HostAndPortTransformingFunction implements Function<SensorEvent<Integer>,String> {

        private final SubnetTier subnetTier;

        public HostAndPortTransformingFunction(SubnetTier subnetTier) {
            this.subnetTier = Preconditions.checkNotNull(subnetTier, "subnetTier");
        }

        @Override
        public String apply(SensorEvent<Integer> event) {
            Integer sensorVal = event.getValue();
            Entity source = event.getSource();
            Maybe<MachineLocation> machine = Machines.findUniqueMachineLocation(source.getLocations());
            if (sensorVal != null && machine.isPresent()) {
                HostAndPort publicTarget = subnetTier.getPortForwardManager().lookup(machine.get(), sensorVal);

                if (publicTarget != null) {
                    log.debug("sensor mapper transforming {} port {} to {}", new Object[] {source, sensorVal, publicTarget});
                    return publicTarget.getHostText()+":"+publicTarget.getPort();
                } else {
                    log.debug("sensor mapper not transforming {} port {}, because no port-mapping for {}", new Object[] {source, sensorVal, machine.get()});
                    return null;
                }
            } else {
                return null;
            }
        }
    }
}
