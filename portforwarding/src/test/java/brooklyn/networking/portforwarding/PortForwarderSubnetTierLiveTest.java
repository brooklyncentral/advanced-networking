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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.entity.basic.EntityLocal;
import org.apache.brooklyn.api.entity.proxying.EntitySpec;
import org.apache.brooklyn.api.event.AttributeSensor;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Protocol;

import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.networking.subnet.SubnetTier;

public class PortForwarderSubnetTierLiveTest extends AbstractPortForwarderIptablesTest {

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(PortForwarderSubnetTierLiveTest.class);

    private SubnetTier subnetTier;

    @Override
    protected boolean requiresMachines() {
        return true;
    }

    // FIXME: explicit ports / port ranges have been temporarily disabled
    @Test(groups={"Live","WIP"})
    public void testOpenPortForwardingAndAdvertise() throws Exception {
        final AttributeSensor<Integer> TARGET_PORT = new BasicAttributeSensor<Integer>(Integer.class, "target.port");
        final AttributeSensor<String> PUBLIC_ENDPOINT = new BasicAttributeSensor<String>(String.class, "endpoint");

        subnetTier = app.createAndManageChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDING_MANAGER, portForwardManager)
                .configure(SubnetTier.PORT_FORWARDER_TYPE, portForwarderType)
                .configure(PortForwarderIptables.FORWARDER_IP, forwarderPublicIp)
                .configure(PortForwarderIptables.FORWARDER_MACHINE, forwarderMachine)
                .configure(SubnetTier.SUBNET_CIDR, Cidr.UNIVERSAL));

        app.start(ImmutableList.of(targetPrivateMachine));

        ((EntityLocal)app).setAttribute(TARGET_PORT, 22);

        subnetTier.openPortForwardingAndAdvertise(
                new EntityAndAttribute<Integer>(app, TARGET_PORT),
                Optional.<Integer>absent(),
                Protocol.TCP,
                Cidr.UNIVERSAL,
                new EntityAndAttribute<String>(app, PUBLIC_ENDPOINT));

        Asserts.succeedsEventually(new Runnable() {
                public void run() {
                    String publicEndpoint = app.getAttribute(PUBLIC_ENDPOINT);
                    assertNotNull(publicEndpoint);
                    assertTrue(publicEndpoint.startsWith(forwarderPublicIp+":"), "publicEndpoint="+publicEndpoint);
                }});

        String endpoint = app.getAttribute(PUBLIC_ENDPOINT);
        Iterable<String> endpointParts = Splitter.on(":").split(endpoint);
        assertEquals(Iterables.size(endpointParts), 2, "endpoint="+endpoint);
        HostAndPort endpointHostAndPort = HostAndPort.fromParts(Iterables.get(endpointParts, 0), Integer.parseInt(Iterables.get(endpointParts, 1)));
        assertTargetReachableVia(endpointHostAndPort);
    }

}
