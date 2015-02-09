package brooklyn.networking.subnet;

import brooklyn.networking.common.subnet.PortForwarder;

import com.google.common.base.Supplier;

/** 
 * Kept for persisted state backwards compatibility
 * 
 * @deprecated since 0.7.0; use {@link brooklyn.networking.common.subnet.PortForwarderClient}
 */
@Deprecated
public class PortForwarderClient extends brooklyn.networking.common.subnet.PortForwarderClient {
    public PortForwarderClient(Supplier<PortForwarder> supplier) {
        super(supplier);
    }
}
