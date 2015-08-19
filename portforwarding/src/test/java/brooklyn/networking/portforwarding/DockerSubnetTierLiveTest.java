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

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.entity.core.EntityAndAttribute;
import org.apache.brooklyn.sensor.core.BasicAttributeSensor;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Protocol;

import brooklyn.networking.subnet.SubnetTier;

public class DockerSubnetTierLiveTest extends AbstractDockerPortForwarderTest {

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(DockerSubnetTierLiveTest.class);

    private SubnetTier subnetTier;

    @Test(groups={"Live"})
    public void testOpenPortForwardingAndAdvertise() throws Exception {
        final AttributeSensor<Integer> TARGET_SSH_PORT = new BasicAttributeSensor<Integer>(Integer.class,
                "target.port");
        final AttributeSensor<Integer> TARGET_HTTP_PORT = new BasicAttributeSensor<Integer>(Integer.class, "target.http.port");
        final AttributeSensor<String> SSH_ENDPOINT = new BasicAttributeSensor<String>(String.class, "ssh.endpoint");
        final AttributeSensor<String> HTTP_ENDPOINT = new BasicAttributeSensor<String>(String.class, "http.endpoint");

        subnetTier = app.createAndManageChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER, portForwarder)
                .configure(SubnetTier.SUBNET_CIDR, Cidr.UNIVERSAL));

        app.start(ImmutableList.of(privateMachine));

        app.setAttribute(TARGET_SSH_PORT, 22);
        app.setAttribute(TARGET_HTTP_PORT, 8080);

        Map<Integer, Integer> portMappings = ((DockerPortForwarder) portForwarder).getPortMappings(privateMachine);
        for (Integer inbountPort : portMappings.keySet()) {
            Optional<Integer> optionalTargetInboundPort = Optional.of(portMappings.get(inbountPort));
            if(inbountPort != 22) {
                subnetTier.openPortForwardingAndAdvertise(
                        new EntityAndAttribute<Integer>(app, TARGET_HTTP_PORT),
                        optionalTargetInboundPort,
                        Protocol.TCP,
                        Cidr.UNIVERSAL,
                        new EntityAndAttribute<String>(app, HTTP_ENDPOINT));
            } else {
                subnetTier.openPortForwardingAndAdvertise(
                        new EntityAndAttribute<Integer>(app, TARGET_SSH_PORT),
                        optionalTargetInboundPort,
                        Protocol.TCP,
                        Cidr.UNIVERSAL,
                        new EntityAndAttribute<String>(app, SSH_ENDPOINT));
            }
        }

        Asserts.succeedsEventually(new Runnable() {
                public void run() {
                    String publicEndpoint = app.getAttribute(SSH_ENDPOINT);
                    assertNotNull(publicEndpoint);
                    assertTrue(publicEndpoint.startsWith(publicIp+":"), "publicEndpoint="+publicEndpoint+": expectedPublicIp="+publicIp);
                }});

        String endpoint = app.getAttribute(SSH_ENDPOINT);
        Iterable<String> endpointParts = Splitter.on(":").split(endpoint);
        assertEquals(Iterables.size(endpointParts), 2, "endpoint="+endpoint);

        int port = Integer.parseInt(Iterables.get(endpointParts, 1));
        HostAndPort endpointHostAndPort = HostAndPort.fromParts(Iterables.get(endpointParts, 0), port);
        assertTargetSshableVia(endpointHostAndPort);
    }

}
