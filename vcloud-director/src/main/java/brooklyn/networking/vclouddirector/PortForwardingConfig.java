package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;
import brooklyn.util.net.Protocol;

import com.google.common.base.Objects;
import com.google.common.net.HostAndPort;

public class PortForwardingConfig {
    Protocol protocol;
    String publicIp;
    Integer publicPort;
    HostAndPort target;
    
    public PortForwardingConfig protocol(Protocol val) {
        this.protocol = val; return this;
    }
    
    public PortForwardingConfig publicIp(String val) {
        this.publicIp = val; return this;
    }
    
    public PortForwardingConfig publicPort(int val) {
        this.publicPort = val; return this;
    }
    
    public PortForwardingConfig target(HostAndPort val) {
        this.target = val; return this;
    }
    
    public void checkValid() {
        checkNotNull(protocol, "protocol");
        checkNotNull(publicIp, "publicIp");
        checkNotNull(publicPort, publicPort);
        checkNotNull(target, "target");
    }
    
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PortForwardingConfig)) return false;
        PortForwardingConfig o = (PortForwardingConfig) obj;
        return Objects.equal(protocol, o.protocol) 
                && Objects.equal(publicIp, o.publicIp) && Objects.equal(publicPort, o.publicPort)
                && Objects.equal(target, o.target);
    }
    
    @Override
    public int hashCode() {
        return Objects.hashCode(protocol, publicIp, publicPort, target);
    }
    
    @Override
    public String toString() {
        return Objects.toStringHelper(this).add("protocol", protocol).add("target", target)
                .add("publicIp", publicIp).add("publicPort", publicPort).toString();
    }
}
