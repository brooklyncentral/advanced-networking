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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.net.HostAndPort;

import brooklyn.entity.Entity;
import brooklyn.location.Location;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.management.ManagementContext;
import brooklyn.networking.common.subnet.PortForwarder;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.HasNetworkAddresses;
import brooklyn.util.net.Protocol;

public class PredefinedPortForwarder implements PortForwarder {

    private static final Logger log = LoggerFactory.getLogger(PredefinedPortForwarder.class);

    private PortForwardManager portForwardManager;
    
    public PredefinedPortForwarder() {
    }

    public PredefinedPortForwarder(PortForwardManager portForwardManager) {
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
        if (targetMachine instanceof Location) {
            HostAndPort result = portForwardManager.lookup((Location)targetMachine, targetPort);
            if (result == null) {
                throw new IllegalStateException("Port-mapping not pre-defined for "+targetMachine+":"+targetPort);
            }
            return result;
        } else {
            // Prefer hostname (because within AWS that resolves correctly to private ip, whereas public ip is not reachable)
            throw new IllegalStateException("Unsupported openPortForwarding for targetMachine that is not a location ("+targetMachine+"), for port "+targetPort);
        }
    }

    @Override
    public HostAndPort openPortForwarding(HostAndPort targetSide, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        if (log.isDebugEnabled()) log.debug("no-op in {} for openPortForwarding({}, {}, {}, {})", new Object[] {this, targetSide, optionalPublicPort, protocol, accessingCidr});
        throw new IllegalStateException("Unsupported openPortForwarding for hostAndPort "+targetSide+"; only locations supported");
    }

    @Override
    public boolean closePortForwarding(HostAndPort targetSide, HostAndPort publicSide, Protocol protocol) {
        if (log.isDebugEnabled()) log.debug("no-op in {} for closePortForwarding({}, {}, {})", new Object[] {this, targetSide, publicSide, protocol});
        return false;
    }

    @Override
    public boolean closePortForwarding(HasNetworkAddresses machine, int targetPort, HostAndPort publicSide, Protocol protocol) {
        if (log.isDebugEnabled()) log.debug("no-op in {} for closePortForwarding({}, {}, {}, {})", new Object[] {this, machine, targetPort, publicSide, protocol});
        return false;
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
