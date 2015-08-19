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
package brooklyn.networking.portforwarding.subnet;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Optional;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.location.access.BrooklynAccessUtils;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.location.jclouds.AbstractJcloudsSubnetSshMachineLocation;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Protocol;

import brooklyn.networking.common.subnet.PortForwarder;

public class JcloudsPortforwardingSubnetMachineLocation extends AbstractJcloudsSubnetSshMachineLocation {
    private static final long serialVersionUID = 252415164250662535L;

    public static final ConfigKey<PortForwarder> PORT_FORWARDER = ConfigKeys.newConfigKey(PortForwarder.class, "portForwarder");

    public static final ConfigKey<PortForwardManager> PORT_FORWARDING_MANAGER = BrooklynAccessUtils.PORT_FORWARDING_MANAGER;

    @Override
    public HostAndPort getSocketEndpointFor(Cidr accessor, int privatePort) {
        PortForwardManager pfw = getRequiredConfig(PORT_FORWARDING_MANAGER);
        PortForwarder portForwarder = getRequiredConfig(PORT_FORWARDER);
        synchronized (pfw) {
            HostAndPort result = pfw.lookup(this, privatePort);
            if (result!=null) return result;

            result = pfw.lookup(getJcloudsId(), privatePort);
            if (result!=null) return result;

            // TODO What to use for associate's publicIpId?
            result = portForwarder.openPortForwarding(this, privatePort, Optional.<Integer>absent(), Protocol.TCP, accessor);
            pfw.associate(getJcloudsId(), result, this, privatePort);
            return result;
        }
    }

    protected <T> T getRequiredConfig(ConfigKey<T> key) {
        return checkNotNull(getConfig(key), key.getName());
    }
}
