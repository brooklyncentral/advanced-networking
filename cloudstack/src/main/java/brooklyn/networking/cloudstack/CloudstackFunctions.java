/*
 * Copyright 2013-2015 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.networking.cloudstack;

import java.util.List;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

import org.jclouds.cloudstack.domain.NIC;
import org.jclouds.cloudstack.domain.VirtualMachine;

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
