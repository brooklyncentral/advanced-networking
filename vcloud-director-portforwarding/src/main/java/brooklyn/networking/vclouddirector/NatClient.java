package brooklyn.networking.vclouddirector;

import com.google.common.net.HostAndPort;

public interface NatClient {

    public HostAndPort openPortForwarding(PortForwardingConfig args);
    
    public HostAndPort closePortForwarding(PortForwardingConfig args);
}
