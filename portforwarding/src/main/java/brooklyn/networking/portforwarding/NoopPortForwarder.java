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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.location.MachineLocation;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.networking.subnet.PortForwarder;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.net.HostAndPort;

public class NoopPortForwarder implements PortForwarder {

    private static final Logger log = LoggerFactory.getLogger(NoopPortForwarder.class);

    public NoopPortForwarder() {
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
    public HostAndPort openPortForwarding(MachineLocation targetMachine, int targetPort, Optional<Integer> optionalPublicPort,
            Protocol protocol, Cidr accessingCidr) {
        if (log.isDebugEnabled()) log.debug("no-op in {} for openPortForwarding({}, {}, {}, {}, {})", new Object[] {this, targetMachine, targetPort, optionalPublicPort, protocol, accessingCidr});
        return HostAndPort.fromParts(targetMachine.getAddress().getHostAddress(), targetPort);
    }

    @Override
    public HostAndPort openPortForwarding(HostAndPort targetSide, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
        if (log.isDebugEnabled()) log.debug("no-op in {} for openPortForwarding({}, {}, {}, {})", new Object[] {this, targetSide, optionalPublicPort, protocol, accessingCidr});
        return targetSide;
    }

    @Override
    public PortForwardManager getPortForwardManager() {
        return null;
    }

    @Override
    public boolean isClient() {
        return false;
    }

}
