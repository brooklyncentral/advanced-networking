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

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.EnricherSpec;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.location.Machines;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.core.sensor.BasicSensorEvent;
import org.apache.brooklyn.enricher.stock.Transformer;
import org.apache.brooklyn.util.core.flags.SetFromFlag;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.net.HostAndPort;

public class SubnetEnrichers {

    private static final Logger log = LoggerFactory.getLogger(SubnetEnrichers.class);

    public static EnricherSpec<?> uriTransformingEnricher(SubnetTier subnetTier, AttributeSensor<String> original, AttributeSensor<String> target) {
        return EnricherSpec.create(UriTransformingEnricher.class)
                .configure(UriTransformingEnricher.SUBNET_TIER, subnetTier)
                .configure(UriTransformingEnricher.SOURCE_SENSOR, original)
                .configure(UriTransformingEnricher.TARGET_SENSOR, target)
                .configure(UriTransformingEnricher.ALLOW_CYCLIC_PUBLISHING, true);
    }

    public static EnricherSpec<?> uriTransformingEnricher(SubnetTier subnetTier, EntityAndAttribute<String> original, AttributeSensor<String> target) {
        return EnricherSpec.create(UriTransformingEnricher.class)
                .configure(UriTransformingEnricher.SUBNET_TIER, subnetTier)
                .configure(UriTransformingEnricher.SOURCE_SENSOR, original.getAttribute())
                .configure(UriTransformingEnricher.PRODUCER, original.getEntity())
                .configure(UriTransformingEnricher.TARGET_SENSOR, target)
                .configure(UriTransformingEnricher.ALLOW_CYCLIC_PUBLISHING, true);
    }

    public static EnricherSpec<?> hostAndPortTransformingEnricher(SubnetTier subnetTier, AttributeSensor<Integer> originalPort, AttributeSensor<String> target) {
        return EnricherSpec.create(HostAndPortTransformingEnricher.class)
                .configure(HostAndPortTransformingEnricher.SUBNET_TIER, subnetTier)
                .configure(HostAndPortTransformingEnricher.SOURCE_SENSOR, originalPort)
                .configure(HostAndPortTransformingEnricher.TARGET_SENSOR, target)
                .configure(UriTransformingEnricher.ALLOW_CYCLIC_PUBLISHING, true);
    }

    public static EnricherSpec<?> hostAndPortTransformingEnricher(SubnetTier subnetTier, EntityAndAttribute<Integer> originalPort, AttributeSensor<String> target) {
        return EnricherSpec.create(HostAndPortTransformingEnricher.class)
                .configure(HostAndPortTransformingEnricher.SUBNET_TIER, subnetTier)
                .configure(HostAndPortTransformingEnricher.SOURCE_SENSOR, originalPort.getAttribute())
                .configure(HostAndPortTransformingEnricher.PRODUCER, originalPort.getEntity())
                .configure(HostAndPortTransformingEnricher.TARGET_SENSOR, target)
                .configure(UriTransformingEnricher.ALLOW_CYCLIC_PUBLISHING, true);
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
                public void onAssociationCreated(PortForwardManager.AssociationMetadata metadata) {
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
                public void onAssociationDeleted(PortForwardManager.AssociationMetadata metadata) {
                    // no-op
                }
            };
            getConfig(SUBNET_TIER).getPortForwardManager().addAssociationListener(listener, Predicates.alwaysTrue());
            
            subscriptions().subscribe(producer, AbstractEntity.LOCATION_ADDED, new SensorEventListener<Location>() {
                @Override public void onEvent(SensorEvent<Location> event) {
                    T sensorVal = producer.getAttribute((AttributeSensor<T>)sourceSensor);
                    if (sensorVal != null) {
                        log.debug("Simulating sensor-event on new location-added {}, to trigger transformation by {}", new Object[] {event.getValue(), AbstractNatTransformingEnricher.this});
                        AbstractNatTransformingEnricher.this.onEvent(new BasicSensorEvent<T>(sourceSensor, producer, sensorVal));
                    }
                }});
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

    /**
     * Transforms a URI (or a HostAndPort) string to the mapped value. Or if there is no
     * port-forward rule defined for the given port on this entity's machine, then just 
     * return the source value.
     * 
     * We now handle both URI and HostAndPort to assist backwards compatibility. For example,
     * MongoDBServer.MONGO_SERVER_ENDPOINT changed from being a URI to being a ip:port, but
     * customers are using the {@link SubnetTier#transformUri(EntityAndAttribute)} to map
     * that sensor. When it changed from being a URI, the customer blueprints broke.
     */
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
            if (Strings.isNonBlank(sensorVal) && machine.isPresent()) {
                if (isUri(sensorVal)) {
                    return transformUri(source, machine.get(), sensorVal);
                } else if (isHostAndPort(sensorVal)) {
                    return transformHostAndPort(source, machine.get(), sensorVal);
                } else {
                    log.debug("sensor mapper not transforming {} URI {} because unknown format", source, sensorVal);
                    return sensorVal;
                }
            } else {
                return sensorVal;
            }
        }
        
        private boolean isUri(String val) {
            try {
                URI.create(val);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        
        private boolean isHostAndPort(String val) {
            try {
                HostAndPort.fromString(val);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        
        private String transformUri(Entity source, MachineLocation machine, String sensorVal) {
            URI uri = URI.create(sensorVal);
            int port = uri.getPort();
            if (port != -1) {
                HostAndPort publicTarget = subnetTier.getPortForwardManager().lookup(machine, port);
                if (publicTarget == null) {
                    // TODO What if publicTarget is still null, but will be set soon? We're not subscribed to changes in the PortForwardManager!
                    // TODO Should we return null or sensorVal? In this method we always return sensorVal;
                    //      but in HostAndPortTransformingEnricher we always return null!
                    log.debug("sensor mapper not transforming {} URI {}, because no port-mapping for {}", new Object[] {source, sensorVal, machine});
                    return sensorVal;
                }
                URI result;
                try {
                    result = new URI(uri.getScheme(), uri.getUserInfo(), publicTarget.getHost(), publicTarget.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
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
        }
        
        private String transformHostAndPort(Entity source, MachineLocation machine, String sensorVal) {
            HostAndPort hostAndPort = HostAndPort.fromString(sensorVal);
            int port = hostAndPort.getPortOrDefault(-1);
            if (port != -1) {
                HostAndPort publicTarget = subnetTier.getPortForwardManager().lookup(machine, port);
                if (publicTarget == null) {
                    log.debug("sensor mapper not transforming {} URI {}, because no port-mapping for {}", new Object[] {source, sensorVal, machine});
                    return sensorVal;
                }
                String result = publicTarget.getHost() + ":" + publicTarget.getPort();
                log.debug("sensor mapper transforming URI "+hostAndPort+" to "+result);
                return result;
            } else {
                log.debug("sensor mapper not transforming URI "+hostAndPort+" because defines no port");
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

        @Override
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
                    return publicTarget.getHost()+":"+publicTarget.getPort();
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
