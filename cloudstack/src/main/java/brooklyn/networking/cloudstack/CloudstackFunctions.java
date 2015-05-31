package brooklyn.networking.cloudstack;

import java.util.List;

import org.jclouds.cloudstack.domain.NIC;
import org.jclouds.cloudstack.domain.VirtualMachine;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

public class CloudstackFunctions {

    public static Function<VirtualMachine, List<String>> vmIpAddresses() {
        return new VmIpAddresses();
    }
    
    private static class VmIpAddresses implements Function<VirtualMachine, List<String>> {
        @Override public List<String> apply(VirtualMachine input) {
            if (input == null) return null;
            
            List<String> result = Lists.newArrayList();
            for (NIC nic : input.getNICs()) {
                result.add(nic.getIPAddress());
            }
            return result;
        }
    }
    
    public static Function<NIC, String> nicIpAddress() {
        return new NicIpAddress();
    }
    
    private static class NicIpAddress implements Function<NIC, String> {
        @Override public String apply(NIC input) {
            return (input == null) ? null : input.getIPAddress();
        }
    }
}
