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
package brooklyn.networking.portforwarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Protocol;

// FIXME: Currently failing with NPE

public class PortForwarderIptablesLiveTest extends AbstractPortForwarderIptablesTest {

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(PortForwarderIptablesLiveTest.class);

    @Override
    protected boolean requiresMachines() {
        return true;
    }

    @Test(groups={"Live, WIP"})
    public void testOpenPortForwarding() throws Exception {
        HostAndPort frontEndHostAndPort = portForwarder.openPortForwarding(
                HostAndPort.fromParts(targetPrivateIp, 22),
                Optional.<Integer>absent(),
                Protocol.TCP,
                Cidr.UNIVERSAL);

        assertTargetReachableVia(frontEndHostAndPort);
    }

    @Test(groups={"Live, WIP"})
    public void testOpenPortForwardingOfLocation() throws Exception {
        HostAndPort frontEndHostAndPort = portForwarder.openPortForwarding(
                targetPrivateMachine,
                22,
                Optional.<Integer>absent(),
                Protocol.TCP,
                Cidr.UNIVERSAL);

        assertTargetReachableVia(frontEndHostAndPort);
    }
}
