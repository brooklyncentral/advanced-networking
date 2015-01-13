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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.location.Location;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.management.ManagementContext;
import brooklyn.networking.subnet.PortForwarder;
import brooklyn.networking.util.ConcurrentReachableAddressFinder;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.HasNetworkAddresses;
import brooklyn.util.net.Protocol;
import brooklyn.util.time.Duration;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

public class NoopPortForwarder implements PortForwarder {

    private static final Logger log = LoggerFactory.getLogger(NoopPortForwarder.class);

    private PortForwardManager portForwardManager;
    
    public NoopPortForwarder() {
    }

    public NoopPortForwarder(PortForwardManager portForwardManager) {
        this.portForwardManager = portForwardManager;
    }

    @Override
    public void injectManagementContext(ManagementContext managementContext) {
        if (portForwardManager == null) {
            portForwardManager = (PortForwardManager) managementContext.getLocationRegistry().resolve("portForwardManager(scope=global)");
        }
    }
    
    @Override
    public void inject(Entity owner, List<Location> locations) {
        // no-op
    }

    @Override
    public String openGateway() {
        // TODO What to return here? Called by SubnetTierImpl.openAndRegisterGateway, called by SubnetTierImpl.start
        return "nogateway";
    }

    @Override
    public String openStaticNat(Entity serviceToOpen) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void openFirewallPort(Entity entity, int port, Protocol protocol, Cidr accessingCidr) {
        if (log.isDebugEnabled()) log.debug("no-op in {} for openFirewallPort({}, {}, {}, {})", new Object[] {this, entity, port, protocol, accessingCidr});
    }

    @Override
    public void openFirewallPortRange(Entity entity, PortRange portRange, Protocol protocol, Cidr accessingCidr) {
        if (log.isDebugEnabled()) log.debug("no-op in {} for openFirewallPortRange({}, {}, {}, {})", new Object[] {this, entity, portRange, protocol, accessingCidr});
    }

    @Override
    public HostAndPort openPortForwarding(HasNetworkAddresses targetMachine, int targetPort, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr) {
        // Prefer hostname (because within AWS that resolves correctly to private ip, whereas public ip is not reachable)
        String targetIp = null;
        String hostname = targetMachine.getHostname();
        if (hostname != null) {
            try {
                InetAddress.getByName(hostname).getAddress();
                targetIp = hostname;
            } catch (UnknownHostException e) {
                if (log.isDebugEnabled()) log.debug("Unable to resolve host "+hostname+"; falling back to IPs");
            }
        }
        if (targetIp == null) {
            Iterable<String> addresses = Iterables.concat(targetMachine.getPublicAddresses(), targetMachine.getPrivateAddresses());
            ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
            try {
                targetIp = new ConcurrentReachableAddressFinder(executor).findReachable(addresses, Duration.ONE_MINUTE);
            } catch (NoSuchElementException e2) {
                // Fall back to preferring public IP, then private IP
                targetIp = Iterables.getFirst(addresses, null);
                if (targetIp == null) {
                    throw new IllegalStateException("No addresses resolvable for target machine "+targetMachine);
                }
                log.warn("Could not resolve reachable address for "+targetMachine+"; falling back to first address "+targetIp, e2);
            } finally {
                executor.shutdownNow();
            }
        }
        if (log.isDebugEnabled()) log.debug("no-op in {} for openPortForwarding({}, {}, {}, {}, {}); returning {}:{}", new Object[] {this, targetMachine, targetPort, optionalPublicPort, protocol, accessingCidr, targetIp, targetPort});
        return HostAndPort.fromParts(targetIp, targetPort);
    }

    @Override
    public HostAndPort openPortForwarding(HostAndPort targetSide, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        if (log.isDebugEnabled()) log.debug("no-op in {} for openPortForwarding({}, {}, {}, {})", new Object[] {this, targetSide, optionalPublicPort, protocol, accessingCidr});
        return targetSide;
    }

    @Override
    public PortForwardManager getPortForwardManager() {
        return portForwardManager;
    }

    @Override
    public boolean isClient() {
        return false;
    }

}
