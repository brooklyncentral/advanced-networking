package brooklyn.networking.subnet;

import brooklyn.entity.basic.EntityLocal;
import brooklyn.location.access.PortForwardManager;
import brooklyn.networking.common.subnet.PortForwarder;

/** 
 * Kept for persisted state backwards compatibility
 * 
 * @deprecated since 0.7.0; use {@link brooklyn.networking.common.subnet.PortForwarderAsyncImpl}
 */
@Deprecated
public class PortForwarderAsyncImpl extends brooklyn.networking.common.subnet.PortForwarderAsyncImpl {

    public PortForwarderAsyncImpl(EntityLocal adjunctEntity,
            PortForwarder portForwarder, PortForwardManager portForwardManager) {
        super(adjunctEntity, portForwarder, portForwardManager);
    }

}
