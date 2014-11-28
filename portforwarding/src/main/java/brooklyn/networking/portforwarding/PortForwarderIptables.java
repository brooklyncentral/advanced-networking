/*
 * Copyright 2013-2014 by Cloudsoft Corporation Limited
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
package brooklyn.networking.portforwarding;

import static brooklyn.util.ssh.BashCommands.sudo;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.location.Location;
import brooklyn.location.MachineLocation;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.basic.PortRanges;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.management.ManagementContext;
import brooklyn.networking.subnet.PortForwarder;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.HasNetworkAddresses;
import brooklyn.util.net.Protocol;
import brooklyn.util.ssh.IptablesCommands;
import brooklyn.util.ssh.IptablesCommands.Chain;
import brooklyn.util.ssh.IptablesCommands.Policy;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.net.HostAndPort;

public class PortForwarderIptables implements PortForwarder {

    // FIXME Currently ignores the protocol passed in, and always does TCP (without checking!)

    // FIXME How to pass FORWARDER_MACHINE in a yaml blueprint?
    // Should we pass a location spec string instead (for a single host)?
    
    private static final Logger log = LoggerFactory.getLogger(PortForwarderIptables.class);

    public static final ConfigKey<String> FORWARDER_IP = ConfigKeys.newStringConfigKey(
            "advancednetworking.iptables.forwarder.ip",
            "The public IP address of the machine to use for port-forwarding");

    public static final ConfigKey<SshMachineLocation> FORWARDER_MACHINE = ConfigKeys.newConfigKey(
            SshMachineLocation.class,
            "advancednetworking.iptables.forwarder.machine",
            "The machine to which iptables port-forwarding rules should be added (corresponding to the advancednetworking.iptables.forwarder.ip)");


    private PortForwardManager portForwardManager;
    private String forwarderIp;
    private SshMachineLocation forwarderMachine;

    public PortForwarderIptables() {
    }
    
    public PortForwarderIptables(String forwarderIp, SshMachineLocation forwarderMachine) {
        this(null, forwarderIp, forwarderMachine);
    }

    public PortForwarderIptables(PortForwardManager portForwardManager, String forwarderIp, SshMachineLocation forwarderMachine) {
        this.portForwardManager = portForwardManager;
        this.forwarderIp = forwarderIp;
        this.forwarderMachine = forwarderMachine;
    }

    @Override
    public void injectManagementContext(ManagementContext managementContext) {
        if (portForwardManager == null) {
            portForwardManager = (PortForwardManager) managementContext.getLocationRegistry().resolve("portForwardManager(scope=global)");
        }
    }
    
    @Override
    public void inject(Entity owner, List<Location> locations) {
        if (forwarderIp == null) {
            forwarderIp = owner.getConfig(FORWARDER_IP);
        }
        if (forwarderMachine == null) {
            forwarderMachine = owner.getConfig(FORWARDER_MACHINE);
        }
    }
    
    public PortForwardManager getPortForwardManager() {
        return portForwardManager;
    }

    @Override
    public String openGateway() {
        // IP of port-forwarder already exists
        return forwarderIp;
    }

    @Override
    public String openStaticNat(Entity serviceToOpen) {
        throw new UnsupportedOperationException("Can only open individual ports; not static nat with iptables");
    }

    @Override
    public void openFirewallPort(Entity entity, int port, Protocol protocol, Cidr accessingCidr) {
        openFirewallPortRange(entity, PortRanges.fromInteger(port), protocol, accessingCidr);
    }

    @Override
    public void openFirewallPortRange(Entity entity, PortRange portRange, Protocol protocol, Cidr accessingCidr) {
        // TODO ignoring; assumes that port-forwarder has all ports open (!) and that
        // vanilla brooklyn code will have opened required ports on the target machine

        // TODO publicIp is passed in constructor as well
    }

    @Override
    public HostAndPort openPortForwarding(HasNetworkAddresses targetMachine, int targetPort, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr) {

    	String targetIp = Iterables.getFirst(Iterables.concat(targetMachine.getPrivateAddresses(), targetMachine.getPublicAddresses()), null);
        if (targetIp==null) {
            throw new IllegalStateException("Failed to open port-forarding for machine "+targetMachine+" because its location has no target ip: "+targetMachine);
        }

        HostAndPort targetSide = HostAndPort.fromParts(targetIp, targetPort);
        HostAndPort newFrontEndpoint = openPortForwarding(targetSide, optionalPublicPort, protocol, accessingCidr);

        log.debug("Enabled port-forwarding for {} port {} (VM {}), via {}", new Object[] {targetMachine, targetPort, targetMachine, newFrontEndpoint});
        return newFrontEndpoint;
    }

    @Override
    public HostAndPort openPortForwarding(HostAndPort targetSide, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        // TODO Could check old mapping, and re-use that public port
        PortForwardManager pfw = getPortForwardManager();

        int publicPort;
        if (optionalPublicPort.isPresent()) {
            publicPort = optionalPublicPort.get();
        } else {
            publicPort = pfw.acquirePublicPort(forwarderIp);
        }

        systemCreatePortForwarding(HostAndPort.fromParts(forwarderIp, publicPort), targetSide, accessingCidr);

        return HostAndPort.fromParts(forwarderIp, publicPort);
    }

    protected boolean systemCreatePortForwarding(HostAndPort publicSide, Location targetVm, int targetPort, Cidr cidr) {
        String targetIp = ((MachineLocation)targetVm).getAddress().getHostAddress();
        if (targetIp==null) {
            log.warn("Skipping creation of port forward rule for "+targetVm+" port "+targetPort+" because location's IP cannot be resolved");
            // throw?
            return false;
        }

        return systemCreatePortForwarding(publicSide, HostAndPort.fromParts(targetIp, targetPort), cidr);
    }

    protected boolean systemCreatePortForwarding(HostAndPort publicSide, HostAndPort targetSide, Cidr cidr) {
        checkNotNull(publicSide, "publicSide");
        checkArgument(publicSide.getHostText().equals(forwarderIp), "publicSide %s should match forwarderIp %s", publicSide, forwarderIp);
        checkNotNull(targetSide, "targetSide");

        try {
            List<String> commands = ImmutableList.of(
                    sudo(String.format("/sbin/iptables -t nat -I PREROUTING -p tcp --dport %s -j DNAT --to-destination %s:%s", publicSide.getPort(), targetSide.getHostText(), targetSide.getPort())),
                    sudo("/sbin/iptables -t nat -I POSTROUTING -j MASQUERADE"),
                    IptablesCommands.saveIptablesRules()); // note save already wrapped in sudo

            int result = forwarderMachine.execScript("port-forwarding "+publicSide+"->"+targetSide, commands);

            boolean opened = systemOpenFirewall(publicSide.getHostText(), publicSide.getPort(), publicSide.getPort(), Protocol.TCP, cidr);
            // targetPort doesn't need to be opened - assuming both on internal network, and already opened

            if (result != 0) {
                log.error("Failed creating port forwarding rule on {}: {} -> {}", new Object[] {this, publicSide, targetSide});
                // it might already be created, so don't crash and burn too hard!
                return false;
            }
            if (!opened) {
                log.error("Failed opening forwarding port on {}: {} -> {}", new Object[] {this, publicSide, targetSide});
                // it might already be created, so don't crash and burn too hard!
                return false;
            }
        } catch (Exception e) {
            log.error("Failed creating port forwarding rule on {}: {} -> {}", new Object[] {this, publicSide, targetSide});
            // it might already be created, so don't crash and burn too hard!
            return false;
        }

        return true;
    }

    protected boolean systemOpenFirewall(String publicIp, int lowerBoundPort, int upperBoundPort, Protocol protocol, Cidr cidr) {
        checkNotNull(publicIp, "publicIp");
        checkNotNull(protocol, "protocol");
        checkArgument(publicIp.equals(forwarderIp), "publicIp %s should match forwarderIp %s", publicIp, forwarderIp);

        try {
            List<String> commands = Lists.newArrayList();
            for (int i = lowerBoundPort; i <= upperBoundPort; i++) {
               commands.add(IptablesCommands.insertIptablesRule(Chain.INPUT, protocol, i, Policy.ACCEPT));
            }
            commands.add(IptablesCommands.saveIptablesRules());

            int result = forwarderMachine.execScript("open-ports "+publicIp+":"+lowerBoundPort+"-"+upperBoundPort, commands);

            if (result != 0) {
                log.error("Failed opening ports on {}: {}:{}-{}", new Object[] {this, publicIp, lowerBoundPort, upperBoundPort});
                // it might already be created, so don't crash and burn too hard!
                return false;
            }
        } catch (Exception e) {
            log.error("Failed opening ports on on {}: {}:{}-{}", new Object[] {this, publicIp, lowerBoundPort, upperBoundPort});
            // it might already be created, so don't crash and burn too hard!
            return false;
        }

        return true;
    }

    @Override
    public boolean isClient() {
        return false;
    }

}
