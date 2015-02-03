package brooklyn.networking.cloudstack.portforwarding;

import static java.lang.String.format;

import java.util.List;

import org.jclouds.cloudstack.domain.PortForwardingRule;
import org.jclouds.cloudstack.domain.PublicIPAddress;
import org.jclouds.cloudstack.domain.VirtualMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.location.Location;
import brooklyn.location.MachineLocation;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.management.ManagementContext;
import brooklyn.networking.cloudstack.CloudstackNew40FeaturesClient;
import brooklyn.networking.common.subnet.PortForwarder;
import brooklyn.networking.subnet.SubnetTier;
import brooklyn.util.guava.Maybe;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.HasNetworkAddresses;
import brooklyn.util.net.Protocol;

public class CloudstackPortForwarder implements PortForwarder {

    private static final Logger log = LoggerFactory.getLogger(CloudstackPortForwarder.class);

    public static final ConfigKey<String> DEFAULT_GATEWAY = ConfigKeys.newStringConfigKey("default.gateway",
            "Default gateway IP for public traffic", "10.255.129.1");
    private final Object mutex = new Object();
    private PortForwardManager portForwardManager;
    private CloudstackNew40FeaturesClient client;
    private SubnetTier subnetTier;
    private JcloudsLocation jcloudsLocation;

    public CloudstackPortForwarder(PortForwardManager portForwardManager) {
        this.portForwardManager = portForwardManager;
    }

    @Override
    public void injectManagementContext(ManagementContext managementContext) {
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
        String ipAddress = String.valueOf(targetVm.getPrivateAddresses().toArray()[0]);
        Maybe<VirtualMachine> vm = client.findVmByIp(ipAddress);

        if (vm.isAbsentOrNull()) {
            log.error("Could not find any VMs with Ip Address:{}", ipAddress);
            return null;
        }
        String networkId = Iterables.getFirst(vm.get().getNICs(), null).getNetworkId();
        Maybe<String> vpcId = client.findVpcIdFromNetworkId(networkId);

        if (vpcId.isAbsent()) {
            log.error("Could not find associated VPCs with Network:{}", networkId);
            return null;
        }

        try {
            synchronized (mutex) {
                Maybe<PublicIPAddress> allocatedPublicIpAddressId = client.findPublicIpAddressByVmId(vm.get().getId());
                PublicIPAddress publicIpAddress;

                if (allocatedPublicIpAddressId.isAbsent()) {
                    publicIpAddress = client.createIpAddressForVpc(vpcId.get());
                } else {
                    publicIpAddress = allocatedPublicIpAddressId.get();
                }

                log.info(format("Opening port:%s on vm:%s with IP:%s", targetPort, vm.get().getId(), publicIpAddress.getIPAddress()));
                client.createPortForwardRuleForVpc(networkId, publicIpAddress.getId(), PortForwardingRule.Protocol.TCP, targetPort, vm.get().getId(), targetPort);
                log.debug("Enabled port-forwarding on {}", publicIpAddress.getIPAddress() + ":" + targetPort);
                return HostAndPort.fromParts(publicIpAddress.getIPAddress(), targetPort);
            }
        } catch (Exception e) {
            log.error("Failed creating port forwarding rule on " + this + " to " + targetPort, e);
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