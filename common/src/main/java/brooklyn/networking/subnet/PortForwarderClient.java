package brooklyn.networking.subnet;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.event.AttributeSensor;
import brooklyn.networking.common.subnet.PortForwarder;
import brooklyn.util.exceptions.Exceptions;

import com.google.common.base.Supplier;

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
