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
import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.location.access.BrooklynAccessUtils;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.jclouds.AbstractJcloudsSubnetSshMachineLocation;
import brooklyn.networking.subnet.PortForwarder;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.net.HostAndPort;

public class JcloudsPortforwardingSubnetMachineLocation extends AbstractJcloudsSubnetSshMachineLocation {
    private static final long serialVersionUID = 252415164250662535L;

    public static final ConfigKey<PortForwarder> PORT_FORWARDER = ConfigKeys.newConfigKey(PortForwarder.class, "portForwarder");

    public static final ConfigKey<PortForwardManager> PORT_FORWARDING_MANAGER = BrooklynAccessUtils.PORT_FORWARDING_MANAGER;

    @Override
    public HostAndPort getSocketEndpointFor(Cidr accessor, int privatePort) {
        PortForwardManager pfw = getRequiredConfig(PORT_FORWARDING_MANAGER);
        PortForwarder portForwarder = getRequiredConfig(PORT_FORWARDER);
        synchronized (pfw) {
            HostAndPort hp = pfw.lookup(this, privatePort);
            if (hp!=null) return hp;

            // TODO What to use for associate's publicIpId?
            HostAndPort result = portForwarder.openPortForwarding(this, privatePort, Optional.<Integer>absent(), Protocol.TCP, accessor);
            pfw.associate(getJcloudsId(), result.getPort(), this, privatePort);
            return result;
        }
    }

    protected <T> T getRequiredConfig(ConfigKey<T> key) {
        return checkNotNull(getConfig(key), key.getName());
    }
}
