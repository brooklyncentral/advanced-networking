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

import static org.testng.Assert.assertEquals;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.mgmt.rebind.RebindTestFixtureWithApp;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Protocol;

import brooklyn.networking.subnet.SubnetTier;

public class VcloudDirectorSubnetTierRebindLiveTest extends RebindTestFixtureWithApp {

    public static final int STARTING_PORT = 19980;
    
    private static final String LOCATION_SPEC = "canopy-vCHS";
    public static final String EXISTING_NETWORK_NAME = "M523007043-2739-default-routed";
    public static final String AVAILABLE_PUBLIC_IP = "23.92.230.8";
    public static final String EXPECTED_ENDPOINT = AVAILABLE_PUBLIC_IP+":"+STARTING_PORT;


    final AttributeSensor<Integer> PRIVATE_PORT = Sensors.newIntegerSensor("mapped.port");
    final AttributeSensor<String> MAPPED_ENDPOINT = Sensors.newStringSensor("mapped.endpoint");
    
    protected JcloudsLocation origLoc;
    protected SshMachineLocation origMachine;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        origLoc = (JcloudsLocation) origManagementContext.getLocationRegistry().resolve(LOCATION_SPEC);
        
        origMachine = origManagementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("user", "myuser")
                .configure("address", "192.168.109.10"));

    }
    
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }
    
    @Test(groups="Live")
    public void testRebindSimple() throws Exception {
        // Create + start entities
        SubnetTier origSubnetTier = origApp.addChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER, new PortForwarderVcloudDirector())
                .configure(PortForwarderVcloudDirector.NETWORK_NAME, EXISTING_NETWORK_NAME)
                .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, AVAILABLE_PUBLIC_IP));
        final TestEntity origEntity = origSubnetTier.addChild(EntitySpec.create(TestEntity.class)
                .location(origMachine));
        Entities.manage(origSubnetTier);
        origApp.start(ImmutableList.of(origLoc));

        // Ensure the DNAT service has been used, prior to rebind
        origSubnetTier.openPortForwardingAndAdvertise(
                EntityAndAttribute.create(origEntity, PRIVATE_PORT), 
                Optional.of(STARTING_PORT),
                Protocol.TCP,
                Cidr.UNIVERSAL,
                Optional.of(new EntityAndAttribute<>(origEntity, MAPPED_ENDPOINT)),
                Optional.<EntityAndAttribute<String>>absent());
        try {
            // Confirm that subnet tier (and port forwarding calls) still work
            origEntity.sensors().set(PRIVATE_PORT, 1234);
            
            Asserts.succeedsEventually(new Runnable() {
                public void run() {
                    assertEquals(origEntity.getAttribute(MAPPED_ENDPOINT), EXPECTED_ENDPOINT);
                }});
            
            // rebind
            rebind();
            SubnetTier newSubnetTier = (SubnetTier) Iterables.find(newApp.getChildren(), Predicates.instanceOf(SubnetTier.class));
            TestEntity newEntity = (TestEntity) Iterables.find(newSubnetTier.getChildren(), Predicates.instanceOf(TestEntity.class));
    
            assertEquals(newEntity.getAttribute(MAPPED_ENDPOINT), EXPECTED_ENDPOINT);
        } finally {
            ((PortForwarderVcloudDirector) origSubnetTier.getPortForwarder()).closePortForwarding(EntityAndAttribute.create(origEntity, PRIVATE_PORT), STARTING_PORT);
        }
    }

    // FIXME Fails with newEntity not getting its expected mapped_endpoint set
    @Test(groups={"Live", "WIP"}, enabled=false)
    public void testRebindHasExisingPortForwardingSubscriptionsActive() throws Exception {
        SubnetTier origSubnetTier = origApp.addChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER, new PortForwarderVcloudDirector())
                .configure(PortForwarderVcloudDirector.NETWORK_NAME, EXISTING_NETWORK_NAME)
                .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, AVAILABLE_PUBLIC_IP));
        final TestEntity origEntity = origSubnetTier.addChild(EntitySpec.create(TestEntity.class)
                .location(origMachine));
        Entities.manage(origSubnetTier);
        origApp.start(ImmutableList.of(origLoc));
        
        origSubnetTier.openPortForwardingAndAdvertise(
                EntityAndAttribute.create(origEntity, PRIVATE_PORT), 
                Optional.of(5678),
                Protocol.TCP,
                Cidr.UNIVERSAL,
                Optional.of(new EntityAndAttribute<>(origEntity, MAPPED_ENDPOINT)),
                Optional.<EntityAndAttribute<String>>absent());

        // rebind
        rebind();
        SubnetTier newSubnetTier = (SubnetTier) Iterables.find(newApp.getChildren(), Predicates.instanceOf(SubnetTier.class));
        final TestEntity newEntity = (TestEntity) Iterables.find(newSubnetTier.getChildren(), Predicates.instanceOf(TestEntity.class));
        
        // Confirm that the port-forwarding registered pre-rebind picks up the change.
        newEntity.sensors().set(PRIVATE_PORT, 1234);
        
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                assertEquals(newEntity.getAttribute(MAPPED_ENDPOINT), EXPECTED_ENDPOINT);
            }});
    }

    // FIXME Fails with newEntity not getting its expected mapped_endpoint set
    @Test(groups={"Live", "WIP"}, enabled=false)
    public void testRebindHasUsablePortForwarding() throws Exception {
        SubnetTier origSubnetTier = origApp.addChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER, new PortForwarderVcloudDirector())
                .configure(PortForwarderVcloudDirector.NETWORK_NAME, EXISTING_NETWORK_NAME)
                .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, AVAILABLE_PUBLIC_IP));
        final TestEntity origEntity = origSubnetTier.addChild(EntitySpec.create(TestEntity.class)
                .location(origMachine));
        Entities.manage(origSubnetTier);
        origApp.start(ImmutableList.of(origLoc));
        
        // rebind
        rebind();
        SubnetTier newSubnetTier = (SubnetTier) Iterables.find(newApp.getChildren(), Predicates.instanceOf(SubnetTier.class));
        final TestEntity newEntity = (TestEntity) Iterables.find(newSubnetTier.getChildren(), Predicates.instanceOf(TestEntity.class));
        
        // Confirm that the port-forwarding is still usable
        newSubnetTier.openPortForwardingAndAdvertise(
                EntityAndAttribute.create(origEntity, PRIVATE_PORT), 
                Optional.of(5678),
                Protocol.TCP,
                Cidr.UNIVERSAL,
                Optional.of(new EntityAndAttribute<>(origEntity, MAPPED_ENDPOINT)),
                Optional.<EntityAndAttribute<String>>absent());

        newEntity.sensors().set(PRIVATE_PORT, 1234);
        
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                assertEquals(newEntity.getAttribute(MAPPED_ENDPOINT), EXPECTED_ENDPOINT);
            }});
    }
}
