package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkArgument;
import brooklyn.util.net.Protocol;

import com.google.common.base.Predicate;
import com.google.common.net.HostAndPort;
import com.vmware.vcloud.api.rest.schema.GatewayNatRuleType;
import com.vmware.vcloud.api.rest.schema.NatRuleType;

public class NatPredicates {

    public static Predicate<NatRuleType> translatedEndpointEquals(String ip, int port) {
        return new TranslatedTargetEquals(ip, port);
    }

    public static Predicate<NatRuleType> translatedEndpointEquals(HostAndPort hostAndPort) {
        checkArgument(hostAndPort.hasPort(), "must have port");
        return translatedEndpointEquals(hostAndPort.getHostText(), hostAndPort.getPort());
    }

    public static Predicate<NatRuleType> originalEndpointEquals(String ip, int port) {
        return new OriginalTargetEquals(ip, port);
    }
    
    public static Predicate<NatRuleType> originalEndpointEquals(HostAndPort hostAndPort) {
        checkArgument(hostAndPort.hasPort(), "must have port");
        return new OriginalTargetEquals(hostAndPort.getHostText(), hostAndPort.getPort());
    }
    
    public static Predicate<NatRuleType> originalIpEquals(String ip) {
        return new OriginalIpEquals(ip);
    }
    
    public static Predicate<NatRuleType> protocolMatches(Protocol protocol) {
        return new ProtocolEquals(protocol);
    }

    protected static class TranslatedTargetEquals implements Predicate<NatRuleType> {
        private final String ip;
        private final int port;
        
        public TranslatedTargetEquals(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
        @Override
        public boolean apply(NatRuleType input) {
            GatewayNatRuleType rule = (input == null) ? null : input.getGatewayNatRule();
            return (rule != null) && ip.equals(rule.getTranslatedIp()) && Integer.toString(port).equals(rule.getTranslatedPort()); 
        }
    }
    
    protected static class OriginalTargetEquals implements Predicate<NatRuleType> {
        private final String ip;
        private final int port;
        
        public OriginalTargetEquals(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
        @Override
        public boolean apply(NatRuleType input) {
            GatewayNatRuleType rule = (input == null) ? null : input.getGatewayNatRule();
            return (rule != null) && ip.equals(rule.getOriginalIp()) && Integer.toString(port).equals(rule.getOriginalPort()); 
        }
    }

    protected static class OriginalIpEquals implements Predicate<NatRuleType> {
        private final String ip;
        
        public OriginalIpEquals(String ip) {
            this.ip = ip;
        }
        @Override
        public boolean apply(NatRuleType input) {
            GatewayNatRuleType rule = (input == null) ? null : input.getGatewayNatRule();
            return (rule != null) && ip.equals(rule.getOriginalIp()); 
        }
    }

    protected static class ProtocolEquals implements Predicate<NatRuleType> {
        private final Protocol protocol;

        public ProtocolEquals(Protocol protocol) {
            this.protocol = protocol;
        }
        @Override
        public boolean apply(NatRuleType input) {
            GatewayNatRuleType rule = (input == null) ? null : input.getGatewayNatRule();
            return (rule != null) && protocol.toString().equalsIgnoreCase(rule.getProtocol());
        }
    }

}
