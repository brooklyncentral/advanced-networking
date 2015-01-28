package brooklyn.networking.subnet;

import brooklyn.entity.basic.EntityLocal;
import brooklyn.location.access.PortForwardManager;
import brooklyn.networking.common.subnet.PortForwarder;

/** Kept for persisted state backwards compatibility **/
public class PortForwarderAsyncImpl extends brooklyn.networking.common.subnet.PortForwarderAsyncImpl {

    public PortForwarderAsyncImpl(EntityLocal adjunctEntity,
            PortForwarder portForwarder, PortForwardManager portForwardManager) {
        super(adjunctEntity, portForwarder, portForwardManager);
    }

}
