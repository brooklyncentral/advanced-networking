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

import com.google.common.base.Supplier;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.event.AttributeSensor;

import brooklyn.config.ConfigKey;
import brooklyn.networking.common.subnet.PortForwarder;
import brooklyn.util.exceptions.Exceptions;

/** 
 * Kept for persisted state backwards compatibility.
 * 
 * The inner classes are also preserved, along with the naming of anonymous inner classes (which
 * by default will be $1, $2 etc in the order they are declared).
 * 
 * @deprecated since 0.7.0; use {@link brooklyn.networking.common.subnet.PortForwarderClient}
 */
@Deprecated
public class PortForwarderClient extends brooklyn.networking.common.subnet.PortForwarderClient {
    public PortForwarderClient(Supplier<PortForwarder> supplier) {
        super(supplier);
    }
    
    /**
     * This method will never be called. It is purely to contain the inner class for backwards compatibility, 
     * preserving the parameter names etc.
     */
    private static PortForwarder innerClass_fromMethodOnEntity(final Entity entity, final String getterMethodOnEntity) {
        return new PortForwarderClient(new Supplier<PortForwarder>() {
            @Override
            public PortForwarder get() {
                PortForwarder result;
                try {
                    result = (PortForwarder) entity.getClass().getMethod(getterMethodOnEntity).invoke(entity);
                } catch (Exception e) {
                    Exceptions.propagateIfFatal(e);
                    throw new IllegalStateException("Cannot invoke "+getterMethodOnEntity+" on "+entity+" ("+entity.getClass()+"): "+e, e);
                }
                if (result==null)
                    throw new IllegalStateException("No PortForwarder available via "+getterMethodOnEntity+" on "+entity+" (returned null)");
                return result;
            }
        });
    }
    
    /**
     * This method will never be called. It is purely to contain the inner class for backwards compatibility, 
     * preserving the parameter names etc.
     */
    private static PortForwarder innerClass_fromConfigOnEntity(final Entity entity, final ConfigKey<PortForwarder> configOnEntity) {
        return new PortForwarderClient(new Supplier<PortForwarder>() {
            @Override
            public PortForwarder get() {
                PortForwarder result = (PortForwarder) entity.getConfig(configOnEntity);
                if (result==null)
                    throw new IllegalStateException("No PortForwarder available via "+configOnEntity+" on "+entity+" (returned null)");
                return result;
            }
        });
    }
    
    /**
     * This method will never be called. It is purely to contain the inner class for backwards compatibility, 
     * preserving the parameter names etc.
     */
    private static PortForwarder innerClass_fromAttributeOnEntity(final Entity entity, final AttributeSensor<PortForwarder> attributeOnEntity) {
        return new PortForwarderClient(new Supplier<PortForwarder>() {
            @Override
            public PortForwarder get() {
                PortForwarder result = (PortForwarder) entity.getAttribute(attributeOnEntity);
                if (result==null)
                    throw new IllegalStateException("No PortForwarder available via "+attributeOnEntity+" on "+entity+" (returned null)");
                return result;
            }
        });
    }
}
