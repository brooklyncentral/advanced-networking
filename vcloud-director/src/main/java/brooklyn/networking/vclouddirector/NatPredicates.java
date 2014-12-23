package brooklyn.networking.vclouddirector;

import brooklyn.util.net.Protocol;

import com.google.common.base.Predicate;
import com.vmware.vcloud.api.rest.schema.GatewayNatRuleType;
import com.vmware.vcloud.api.rest.schema.NatRuleType;

public class NatPredicates {

    public static Predicate<NatRuleType> translatedTargetEquals(String ip, int port) {
        return new TranslatedTargetEquals(ip, port);
    }
    
    public static Predicate<NatRuleType> originalTargetEquals(String ip, int port) {
        return new OriginalTargetEquals(ip, port);
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
