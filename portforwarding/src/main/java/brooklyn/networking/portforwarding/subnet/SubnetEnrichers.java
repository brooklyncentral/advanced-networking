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
package brooklyn.networking.portforwarding.subnet;

import java.net.URI;
import java.net.URISyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.enricher.basic.Transformer;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.event.AttributeSensor;
import brooklyn.event.SensorEvent;
import brooklyn.location.MachineLocation;
import brooklyn.location.basic.Machines;
import brooklyn.policy.EnricherSpec;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.guava.Maybe;
import brooklyn.util.text.Strings;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;

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

    public static class UriTransformingEnricher extends Transformer<Object, String> {

        @SetFromFlag("subnetTier")
        public static final ConfigKey<SubnetTier> SUBNET_TIER = ConfigKeys.newConfigKey(SubnetTier.class, "enricher.uriTransformer.subnetTier");

        @Override
        public void init() {
            SubnetTier subnetTier = getConfig(SUBNET_TIER);
            setConfig(TRANSFORMATION_FROM_EVENT, new UriTransformingFunction(subnetTier));
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

    public static class HostAndPortTransformingEnricher extends Transformer<Object, String> {

        @SetFromFlag("subnetTier")
        public static final ConfigKey<SubnetTier> SUBNET_TIER = ConfigKeys.newConfigKey(SubnetTier.class, "enricher.uriTransformer.subnetTier");

        public void init() {
            SubnetTier subnetTier = getConfig(SUBNET_TIER);
            setConfig(TRANSFORMATION_FROM_EVENT, new HostAndPortTransformingFunction(subnetTier));
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
