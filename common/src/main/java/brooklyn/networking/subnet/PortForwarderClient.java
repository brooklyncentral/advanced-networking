package brooklyn.networking.subnet;

import brooklyn.networking.common.subnet.PortForwarder;

import com.google.common.base.Supplier;

/** Kept around for persisted state backwards compatibility **/
@Deprecated
public class PortForwarderClient extends brooklyn.networking.common.subnet.PortForwarderClient {
    public PortForwarderClient(Supplier<PortForwarder> supplier) {
        super(supplier);
    }
}
