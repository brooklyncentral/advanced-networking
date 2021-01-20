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
package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.util.concurrent.ExecutorService;

import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.util.net.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.net.HostAndPort;

import brooklyn.networking.vclouddirector.nat.NatServiceLiveTest;
import brooklyn.networking.vclouddirector.nat.PortForwardingConfig;

/**
 * See {@link NatServiceLiveTest} for details of environment setup assumptions. 
 */
public class NatMicroserviceClientLiveTest extends BrooklynAppLiveTestSupport {

    // TODO Also need to improve and test error handling (e.g. the port is already assigned).

    private static final Logger LOG = LoggerFactory.getLogger(NatMicroserviceClientLiveTest.class);

    private static final String LOCATION_SPEC = "canopy-vCHS";

    /**
     * A valid looking address for inside `canopy-vCHS`. There doesn't need to be any VM
     * with this name.
     */
    public static final String INTERNAL_MACHINE_IP = "192.168.109.10";
    
    public static final int STARTING_PORT = 19980;
    public static final PortRange DEFAULT_PORT_RANGE = PortRanges.fromString("11000-19999");

    protected JcloudsLocation loc;
    
    private String publicIp;
    private ExecutorService executor;
    private File endpointsPropertiesFile;

    private NatMicroserviceClient client;

    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_SPEC);
        publicIp = (String) checkNotNull(loc.config().getBag().getStringKey("advancednetworking.vcloud.network.publicip"), "publicip");
        
        // If targeting a shared/production vDC, then double-check this points at the right NAT microservice!
        // If not, then would be ok to run one locally.
        String microserviceUrl = loc.config().get(PortForwarderVcloudDirector.NAT_MICROSERVICE_ENDPOINT);
        assertNotNull(microserviceUrl);

        client = new NatMicroserviceClient(microserviceUrl, loc);
    }
    
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        if (endpointsPropertiesFile != null) endpointsPropertiesFile.delete();
        if (executor != null) executor.shutdownNow();
        super.tearDown();
    }

    // Test assumes there is at least one pre-existing NAT rule
    @Test(groups="Live")
    public void testList() throws Exception {
        String result = client.list();
        LOG.info("Listed: "+result);
        
        String regex = "\\[\\{.*network.*protocol.*originalIp.*originalPort.*translatedIp.*translatedPort.*\\}\\]";
        assertTrue(result.matches(regex), "result="+result);
    }
    
    @Test(groups="Live")
    public void testOpenPortForwardingWithExplicitPort() throws Exception {
        int expectedPort = STARTING_PORT + 5;
        try {
            HostAndPort result = client.openPortForwarding(new PortForwardingConfig()
                    .protocol(Protocol.TCP)
                    .publicEndpoint(HostAndPort.fromParts(publicIp, expectedPort))
                    .targetEndpoint(HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1234)));
            LOG.info("Opened port-forwarding "+INTERNAL_MACHINE_IP+":1234 -> "+result);
            assertEquals(result, HostAndPort.fromParts(publicIp, expectedPort));
        } finally {
            client.closePortForwarding(new PortForwardingConfig()
                    .protocol(Protocol.TCP)
                    .publicEndpoint(HostAndPort.fromParts(publicIp, expectedPort))
                    .targetEndpoint(HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1234)));
        }
    }
    
    // The "publicPortRange" is no longer supported - customer request was for the NAT microservice
    // to always choose the port.
    @Test(groups={"Live", "WIP"}, enabled=false)
    public void testOpenPortForwardingWithExplicitPortRange() throws Exception {
        PortRange portRange = PortRanges.fromString((STARTING_PORT+5)+"-"+(STARTING_PORT+10));
        
        HostAndPort result = client.openPortForwarding(new PortForwardingConfig()
                .protocol(Protocol.TCP)
                .publicEndpoint(HostAndPort.fromString(publicIp))
                .publicPortRange(portRange)
                .targetEndpoint(HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1234)));
        try {
            assertEquals(result.getHost(), publicIp, "result="+result);
            assertTrue(contains(portRange, result.getPort()), "result="+result);
        } finally {
            if (result != null) {
                client.closePortForwarding(new PortForwardingConfig()
                        .protocol(Protocol.TCP)
                        .publicEndpoint(result)
                        .targetEndpoint(HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1234)));
            }
        }
    }

    @Test(groups="Live")
    public void testOpenPortForwarding() throws Exception {
        HostAndPort result = client.openPortForwarding(new PortForwardingConfig()
                .protocol(Protocol.TCP)
                .publicEndpoint(HostAndPort.fromString(publicIp))
                .targetEndpoint(HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1234)));
        try {
            assertEquals(result.getHost(), publicIp, "result="+result);
            assertTrue(contains(DEFAULT_PORT_RANGE, result.getPort()), "result="+result);
        } finally {
            if (result != null) {
                client.closePortForwarding(new PortForwardingConfig()
                        .protocol(Protocol.TCP)
                        .publicEndpoint(result)
                        .targetEndpoint(HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1234)));
            }
        }
    }
    
    protected boolean contains(PortRange range, int port) {
        for (int contender : range) {
            if (contender == port) return true;
        }
        return false;
    }
}
