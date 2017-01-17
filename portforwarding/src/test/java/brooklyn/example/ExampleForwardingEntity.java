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
package brooklyn.example;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;
import org.apache.brooklyn.entity.java.VanillaJavaApp;

import com.google.common.reflect.TypeToken;

@ImplementedBy(ExampleForwardingEntityImpl.class)
public interface ExampleForwardingEntity extends VanillaJavaApp {

    ConfigKey<Entity> ORIGIN_ENTITY_FORWARDED = ConfigKeys.newConfigKey(
            Entity.class, "sshtunnel.origin.entity");

    @SuppressWarnings("serial")
    ConfigKey<AttributeSensor<Integer>> ORIGIN_PORT_ATTRIBUTE_FORWARDED = ConfigKeys.newConfigKey(
            new TypeToken<AttributeSensor<Integer>>() {},
            "sshtunnel.origin.portAttribute");

    // Note this is not set on this entity; it is set on the origin entity!
    AttributeSensor<String> PUBLIC_KEY_DATA = new BasicAttributeSensor<String>(String.class, 
            "sshtunnel.publicKeyData", "public key data for a node");

}
