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
package brooklyn.networking.cloudstack.portforwarding;

import static java.lang.String.format;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

import org.jclouds.cloudstack.domain.NIC;
import org.jclouds.cloudstack.domain.PortForwardingRule;
import org.jclouds.cloudstack.domain.PublicIPAddress;
import org.jclouds.cloudstack.domain.VirtualMachine;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.BasicConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.HasNetworkAddresses;
import org.apache.brooklyn.util.net.Protocol;

import brooklyn.networking.cloudstack.CloudstackNew40FeaturesClient;
import brooklyn.networking.common.subnet.PortForwarder;
import brooklyn.networking.subnet.SubnetTier;

public class CloudstackPortForwarder implements PortForwarder {

    private static final Logger log = LoggerFactory.getLogger(CloudstackPortForwarder.class);

    public static final ConfigKey<String> DEFAULT_GATEWAY = ConfigKeys.newStringConfigKey(
            "default.gateway",
            "Default gateway IP for public traffic",
            "10.255.129.1");
    
    public static final ConfigKey<Boolean> USE_VPC = ConfigKeys.newBooleanConfigKey( 
            "advancednetworking.cloudstack.forwader.useVpc",
            "Whether to use VPC's",
            false);

    private final Object mutex = new Object();
    private PortForwardManager portForwardManager;
    private CloudstackNew40FeaturesClient client;
    private SubnetTier subnetTier;
    private JcloudsLocation jcloudsLocation;

    public CloudstackPortForwarder() {

    }

    public CloudstackPortForwarder(PortForwardManager portForwardManager) {
        this.portForwardManager = portForwardManager;
    }

    @Override
    public void setManagementContext(ManagementContext managementContext) {
        if (portForwardManager == null) {
            portForwardManager = (PortForwardManager) managementContext.getLocationRegistry().resolve("portForwardManager(scope=global)");
        }
    }

    @Override
    public PortForwardManager getPortForwardManager() {
        return portForwardManager;
    }

    @Override
    public void inject(Entity owner, List<Location> locations) {
        subnetTier = (SubnetTier) owner;
        jcloudsLocation = (JcloudsLocation) Iterables.get(locations, 0);
        client = CloudstackNew40FeaturesClient.newInstance(jcloudsLocation);
    }

    @Override
    public String openGateway() {
        return subnetTier.getConfig(DEFAULT_GATEWAY);
    }

    @Override
    public String openStaticNat(Entity serviceToOpen) {
        //TODO: add a static NAT config key to enable static NAT allocation.
        throw new UnsupportedOperationException();
    }

    @Override
    public void openFirewallPort(Entity entity, int port, Protocol protocol, Cidr accessingCidr) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void openFirewallPortRange(Entity entity, PortRange portRange, Protocol protocol, Cidr accessingCidr) {
        throw new UnsupportedOperationException();
    }

    public HostAndPort openPortForwarding(MachineLocation targetVm, int targetPort, Optional<Integer> optionalPublicPort,
                                          Protocol protocol, Cidr accessingCidr) {
        Preconditions.checkNotNull(client);

        return openPortForwarding((HasNetworkAddresses) targetVm, targetPort, optionalPublicPort, protocol, accessingCidr);
    }

    @Override
    public HostAndPort openPortForwarding(HasNetworkAddresses targetVm, int targetPort, Optional<Integer> optionalPublicPort,
                                          Protocol protocol, Cidr accessingCidr) {
        Preconditions.checkNotNull(client);
        final String ipAddress = String.valueOf(targetVm.getPrivateAddresses().toArray()[0]);
        Maybe<VirtualMachine> vm = client.findVmByIp(ipAddress);
        Boolean useVpc = subnetTier.getConfig(USE_VPC);
        int publicPort = optionalPublicPort.isPresent() ? optionalPublicPort.get() : targetPort;
        
        if (vm.isAbsentOrNull()) {
            Map<VirtualMachine, List<String>> vmIpMapping = client.getVmIps();
            log.error("Could not find any VMs with Ip Address {}; contenders: {}", ipAddress, vmIpMapping);
            return null;
        }
        NIC nic = Iterables.find(vm.get().getNICs(), new Predicate<NIC>() {
            @Override public boolean apply(NIC input) {
                return (input == null) ? false : ipAddress.equals(input.getIPAddress());
            }});
        String networkId = nic.getNetworkId();
        
        Maybe<String> vpcId;
        if (useVpc) {
            vpcId = client.findVpcIdFromNetworkId(networkId);
            
            if (vpcId.isAbsent()) {
                log.error("Could not find associated VPCs with Network: {}; continuing without opening port-forwarding", networkId);
                return null;
            }
        } else {
            vpcId = Maybe.absent("use-vpc not enabled");
        }
        
        try {
            synchronized (mutex) {
                Maybe<PublicIPAddress> allocatedPublicIpAddressId = client.findPublicIpAddressByVmId(vm.get().getId());
                PublicIPAddress publicIpAddress;

                if (allocatedPublicIpAddressId.isPresent()) {
                    publicIpAddress = allocatedPublicIpAddressId.get();
                } else if (useVpc) {
                    publicIpAddress = client.createIpAddressForVpc(vpcId.get());
                } else {
                    publicIpAddress = client.createIpAddressForNetwork(networkId);
                }

                log.info(format("Opening port:%s on vm:%s with IP:%s", targetPort, vm.get().getId(), publicIpAddress.getIPAddress()));
                String jobid = client.createPortForwardRule(networkId, publicIpAddress.getId(), PortForwardingRule.Protocol.TCP, publicPort, vm.get().getId(), targetPort);
                client.waitForJobSuccess(jobid);
                log.debug("Enabled port-forwarding on {}", publicIpAddress.getIPAddress() + ":" + publicPort);
                return HostAndPort.fromParts(publicIpAddress.getIPAddress(), publicPort);
            }
        } catch (Exception e) {
            log.error("Failed creating port forwarding rule on " + this + " to " + targetPort+"; continuing", e);
            // it might already be created, so don't crash and burn too hard!
            return null;
        }
    }

    @Override
    public HostAndPort openPortForwarding(HostAndPort target, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean closePortForwarding(HostAndPort targetSide, HostAndPort publicSide, Protocol protocol) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean closePortForwarding(HasNetworkAddresses machine, int targetPort, HostAndPort publicSide, Protocol protocol) {
        // TODO Need support for client.deletePortForwardRuleForVpc(...)
        // Until then, no-op
        return false;
    }

    @Override
    public boolean isClient() {
        return false;
    }
}