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
import static org.testng.Assert.assertTrue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.entity.basic.EntityLocal;
import org.apache.brooklyn.api.entity.proxying.EntitySpec;
import org.apache.brooklyn.api.event.AttributeSensor;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.management.ManagementContext;
import org.apache.brooklyn.location.access.PortForwardManager;
import org.apache.brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import org.apache.brooklyn.location.basic.SshMachineLocation;
import org.apache.brooklyn.test.entity.TestApplication;

import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.networking.subnet.SubnetTier;
import brooklyn.test.Asserts;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Networking;
import brooklyn.util.net.Protocol;

public class NoopPortForwarderSubnetTierIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NoopPortForwarderSubnetTierIntegrationTest.class);

    public static final String LOC_SPEC = "localhost(address=127.0.0.1)";

    protected TestApplication app;
    protected ManagementContext managementContext;
    protected NoopPortForwarder portForwarder;
    protected PortForwardManager portForwardManager;
    protected LocalhostMachineProvisioningLocation loc;

    protected SshMachineLocation targetMachine;

    private SubnetTier subnetTier;

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        app = TestApplication.Factory.newManagedInstanceForTests();
        managementContext = app.getManagementContext();
        portForwardManager = (PortForwardManager) managementContext.getLocationRegistry().resolve("portForwardManager(scope=global)");
        portForwarder = new NoopPortForwarder(portForwardManager);

        // Note: using different username on each to ensure no mix up there!
        loc = (LocalhostMachineProvisioningLocation) managementContext.getLocationRegistry().resolve(LOC_SPEC);
        targetMachine = loc.obtain();
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (managementContext != null) Entities.destroyAll(managementContext);
        managementContext = null;
        app = null;
        portForwarder = null;
    }

    @Test(groups={"Integration"})
    public void testOpenPortForwardingAndAdvertise() throws Exception {
        final AttributeSensor<Integer> TARGET_PORT = new BasicAttributeSensor<Integer>(Integer.class, "target.port");
        final AttributeSensor<String> PUBLIC_ENDPOINT = new BasicAttributeSensor<String>(String.class, "endpoint");

        subnetTier = app.createAndManageChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDING_MANAGER, portForwardManager)
                .configure(SubnetTier.PORT_FORWARDER, portForwarder)
                .configure(SubnetTier.SUBNET_CIDR, Cidr.UNIVERSAL));

        app.start(ImmutableList.of(targetMachine));

        ((EntityLocal)app).setAttribute(TARGET_PORT, 22);

        final String privateEndpoint = targetMachine.getAddress().getHostAddress() + ":22";
        
        subnetTier.openPortForwardingAndAdvertise(
                new EntityAndAttribute<Integer>(app, TARGET_PORT),
                Optional.<Integer>absent(),
                Protocol.TCP,
                Cidr.UNIVERSAL,
                new EntityAndAttribute<String>(app, PUBLIC_ENDPOINT));

        Asserts.succeedsEventually(new Runnable() {
                public void run() {
                    String publicEndpoint = app.getAttribute(PUBLIC_ENDPOINT);
                    assertEquals(publicEndpoint, privateEndpoint);
                }});

        String publicEndpoint = app.getAttribute(PUBLIC_ENDPOINT);
        Iterable<String> endpointParts = Splitter.on(":").split(publicEndpoint);
        assertEquals(Iterables.size(endpointParts), 2, "endpoint="+publicEndpoint);
        HostAndPort endpointHostAndPort = HostAndPort.fromParts(Iterables.get(endpointParts, 0), Integer.parseInt(Iterables.get(endpointParts, 1)));
        assertTargetReachableVia(endpointHostAndPort);
    }

    // TODO Duplication of AbstractPortForwarderIptablesTest.assertTargetReachableVia
    protected void assertTargetReachableVia(HostAndPort hostAndPort) throws Exception {
        log.info("Confirming ssh-reachable: "+targetMachine.getUser()+"@"+hostAndPort.getHostText()+":"+hostAndPort.getPort());

        SshMachineLocation viaForwarding = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", Networking.getInetAddressWithFixedName(hostAndPort.getHostText()))
                .configure("port", hostAndPort.getPort())
                .configure("user", targetMachine.getUser())
                .configure("privateKeyData", targetMachine.getConfig(SshMachineLocation.PRIVATE_KEY_DATA))
                .configure("privateKeyFile", targetMachine.getConfig(SshMachineLocation.PRIVATE_KEY_FILE)));
        try {
            assertTrue(viaForwarding.isSshable(), "hostAndPort="+hostAndPort);
        } finally {
            viaForwarding.close();
            managementContext.getLocationManager().unmanage(viaForwarding);
        }
    }
}
