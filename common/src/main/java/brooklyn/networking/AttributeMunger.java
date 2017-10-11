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
package brooklyn.networking;

import java.util.Map;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;

public class AttributeMunger {

    private static final Logger log = LoggerFactory.getLogger(AttributeMunger.class);

    private final Entity adjunctEntity;

    public AttributeMunger(Entity adjunctEntity) {
        this.adjunctEntity = adjunctEntity;
    }

    public void transformSensorStringReplacingWithPublicAddressAndPort(
            final EntityAndAttribute<String> targetToUpdate,
            final Optional<EntityAndAttribute<Integer>> optionalTargetPort,
            final Iterable<? extends AttributeSensor<String>> targetsToMatch,
            final EntityAndAttribute<String> replacementSource) {
        SensorPropagaterWithReplacement mapper = new SensorPropagaterWithReplacement(targetToUpdate, false, new Function<String,String>() {
            @Override
            public String apply(String sensorVal) {
                if (sensorVal==null) return null;
                String input = targetToUpdate.get();
                String replacementText = replacementSource.get();
                log.debug("sensor mapper transforming address in "+targetToUpdate+", with "+replacementText+" (old value is "+input+")");
                String suffix = "";
                if (optionalTargetPort.isPresent()) {
                    Integer port = optionalTargetPort.get().get();
                    if (port==null) {
                        log.warn("no map-from port available for sensor mapper replacing addresses in "+targetToUpdate+
                                " (listening on "+optionalTargetPort+")");
                        return input;
                    }
                    suffix = ":"+port;
                }
                Map<AttributeSensor<String>, String> targetValsToMatch = Maps.newLinkedHashMap();
                for (AttributeSensor<String> targetToMatch : targetsToMatch) {
                    targetValsToMatch.put(targetToMatch, targetToUpdate.getEntity().getAttribute(targetToMatch));
                }
                String output = input;
                for (String targetValToMatch : targetValsToMatch.values()) {
                    output = replaceIfNotNull(output, targetValToMatch+suffix, replacementText);
                }

                log.debug("sensor mapper transforming address in "+targetToUpdate+": "+
                        "input="+input+"; output="+output+"; suffix="+suffix+
                        "; replacementSource="+replacementSource+
                        "; replacementText="+replacementText+
                        "; targetValsToMatch="+targetValsToMatch);
                return output;
            }
        });

        // TODO Should we subscribe to each of targetsToMatch?
        // And should we subscribe to optionalTargetPort?
        for (AttributeSensor<String> targetToMatch : targetsToMatch) {
            subscribe(targetToUpdate.getEntity(), targetToMatch, mapper);
        }
        subscribe(targetToUpdate.getEntity(), targetToUpdate.getAttribute(), mapper);
        subscribe(replacementSource.getEntity(), replacementSource.getAttribute(), mapper);
        // assume hostname and port are set before the above subscription
        String newval = mapper.apply(targetToUpdate.get());
        if (newval != null) {
            setAttributeIfChanged(targetToUpdate, newval);
        }
    }

    public static class SensorPropagaterWithReplacement implements SensorEventListener<String> {
        private final Function<String, String> function;
        private final EntityAndAttribute<String> toUpdate;
        private final boolean canSetNull;

        public SensorPropagaterWithReplacement(EntityAndAttribute<String> toUpdate, Function<String, String> function) {
            this(toUpdate, true, function);
        }
        public SensorPropagaterWithReplacement(EntityAndAttribute<String> toUpdate, boolean canSetNull, Function<String, String> function) {
            this.toUpdate = toUpdate;
            this.function = function;
            this.canSetNull = canSetNull;
        }
        @Override
        public void onEvent(SensorEvent<String> event) {
            String v = Strings.toString(event.getValue());
            String v2 = apply(v);
            if (v2 != null || canSetNull) {
                setAttributeIfChanged(toUpdate, v2);
            }
        }
        public String apply(String value) {
            return function.apply(value);
        }
    }

    public static String replaceIfNotNull(String string, String searchFor, String replaceWith) {
        if (string==null || searchFor==null || replaceWith==null) return string;
        return Strings.replaceAll(string, searchFor, replaceWith);
    }

    public static <T> void setAttributeIfChanged(EntityAndAttribute<T> entityAndAttribute, T val) {
        setAttributeIfChanged(entityAndAttribute.getEntity(), entityAndAttribute.getAttribute(), val);
    }

    public static <T> void setAttributeIfChanged(Entity entity, AttributeSensor<T> attribute, T val) {
        Object oldval = entity.getAttribute(attribute);
        if (!Objects.equal(oldval, val)) {
            entity.sensors().set(attribute, val);
        }
    }

    private <T> void subscribe(Entity target, AttributeSensor<T> sensor, SensorEventListener<? super T> listener) {
        adjunctEntity.subscriptions().subscribe(target, sensor, listener);
    }
}
