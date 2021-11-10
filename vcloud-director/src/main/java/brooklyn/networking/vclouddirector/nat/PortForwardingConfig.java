package brooklyn.networking.vclouddirector.nat;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.util.net.Protocol;

import com.google.common.base.Objects;
import com.google.common.net.HostAndPort;


public class PortForwardingConfig {
    private Protocol protocol;
    private HostAndPort publicEndpoint;
    private HostAndPort targetEndpoint;
    private PortRange publicPortRange;
    
    public PortForwardingConfig protocol(Protocol val) {
        this.protocol = val;
        return this;
    }
    
    public PortForwardingConfig publicEndpoint(HostAndPort val) {
        this.publicEndpoint = val;
        return this;
    }

    public PortForwardingConfig publicPortRange(PortRange val) {
        this.publicPortRange = val;
        return this;
    }

    public PortForwardingConfig targetEndpoint(HostAndPort val) {
        this.targetEndpoint = val;
        return this;
    }
    
    public void checkValid() {
        checkNotNull(protocol, "protocol");
        checkNotNull(publicEndpoint, "publicEndpoint");
        checkNotNull(targetEndpoint, "targetEndpoint");
        checkState(!(publicEndpoint.hasPort() && publicPortRange != null), 
                "Must not specify port range (%s) and also port in public endpoint (%s)", 
                publicPortRange, publicEndpoint);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PortForwardingConfig)) return false;
        PortForwardingConfig o = (PortForwardingConfig) obj;
        return Objects.equal(protocol, o.protocol) 
                && Objects.equal(publicEndpoint, o.publicEndpoint) 
                && Objects.equal(publicPortRange, o.publicPortRange) 
                && Objects.equal(targetEndpoint, o.targetEndpoint);
    }
    
    @Override
    public int hashCode() {
        return Objects.hashCode(protocol, publicEndpoint, publicPortRange, targetEndpoint);
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public HostAndPort getPublicEndpoint() {
        return publicEndpoint;
    }

    public HostAndPort getTargetEndpoint() {
        return targetEndpoint;
    }

    public PortRange getPublicPortRange() {
        return publicPortRange;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("protocol", protocol).add("targetEndpoint", targetEndpoint)
                .add("publicEndpoint", publicEndpoint).add("publicPortRange", publicPortRange).toString();
    }
}
