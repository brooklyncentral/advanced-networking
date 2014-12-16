package brooklyn.networking.vclouddirector;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.BrooklynAppLiveTestSupport;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.Sensors;
import brooklyn.location.LocationSpec;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.networking.subnet.SubnetTier;
import brooklyn.test.Asserts;
import brooklyn.test.EntityTestUtils;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

public class VcloudDirectorSubnetTierLiveTest extends BrooklynAppLiveTestSupport {

    // TODO Also need to improve and test error handling (e.g. the port is already assigned).
    
    private static final String LOCATION_SPEC = "canopy-vCHS";

    public static final String INTERNAL_MACHINE_IP = "192.168.109.10";
    
    public static final String EXISTING_NETWORK_NAME = "M523007043-2739-default-routed";
    public static final String AVAILABLE_PUBLIC_IP_FOR_NETWORK_NAME = "23.92.230.8";
    
    public static final String EXISTING_NETWORK_ID = "041e176a-befc-4b28-89e2-3c5343ff4d12";
    public static final String AVAILABLE_PUBLIC_IP_FOR_NETWORK_ID = "23.92.230.21";

    protected JcloudsLocation loc;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_SPEC);
    }
    
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }
    
    @Test(groups="Live")
    public void testOpenPortForwardingAndAdvertiseUsingNetworkName() throws Exception {
        final AttributeSensor<Integer> PRIVATE_PORT = Sensors.newIntegerSensor("mapped.port");
        final AttributeSensor<String> MAPPED_ENDPOINT = Sensors.newStringSensor("mapped.endpoint");
        
        final int expectedPort = 5678;
        final String expectedEndpoint = AVAILABLE_PUBLIC_IP_FOR_NETWORK_NAME+":"+expectedPort;

        SshMachineLocation machine = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("user", "myuser")
                .configure("address", INTERNAL_MACHINE_IP));

        try {
            SubnetTier subnetTier = app.addChild(EntitySpec.create(SubnetTier.class)
                    .configure(SubnetTier.PORT_FORWARDER, new PortForwarderVcloudDirector())
                    .configure(PortForwarderVcloudDirector.NETWORK_NAME, EXISTING_NETWORK_NAME)
                    .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, AVAILABLE_PUBLIC_IP_FOR_NETWORK_NAME));
            final TestEntity entity = subnetTier.addChild(EntitySpec.create(TestEntity.class)
                    .location(machine));
            Entities.manage(subnetTier);
            app.start(ImmutableList.of(loc));
            
            entity.setAttribute(PRIVATE_PORT, 1234);
            subnetTier.openPortForwardingAndAdvertise(
                    EntityAndAttribute.supplier(entity, PRIVATE_PORT), 
                    Optional.of(expectedPort),
                    Protocol.TCP,
                    Cidr.UNIVERSAL,
                    EntityAndAttribute.supplier(entity, MAPPED_ENDPOINT));
            
            EntityTestUtils.assertAttributeEqualsEventually(entity, MAPPED_ENDPOINT, expectedEndpoint);
        } finally {
            // TODO Delete DNAT rule
        }
    }
    
    @Test(groups="Live")
    public void testOpenPortForwardingAndAdvertiseUsingNetworkId() throws Exception {
        final AttributeSensor<Integer> PRIVATE_PORT = Sensors.newIntegerSensor("mapped.port");
        final AttributeSensor<String> MAPPED_ENDPOINT = Sensors.newStringSensor("mapped.endpoint");
        
        final int expectedPort = 5678;
        final String expectedEndpoint = AVAILABLE_PUBLIC_IP_FOR_NETWORK_ID+":"+expectedPort;

        SshMachineLocation machine = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("user", "myuser")
                .configure("address", "192.168.109.10"));

        try {
            SubnetTier subnetTier = app.addChild(EntitySpec.create(SubnetTier.class)
                    .configure(SubnetTier.PORT_FORWARDER, new PortForwarderVcloudDirector())
                    .configure(PortForwarderVcloudDirector.NETWORK_ID, EXISTING_NETWORK_ID)
                    .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, AVAILABLE_PUBLIC_IP_FOR_NETWORK_ID));
            final TestEntity entity = subnetTier.addChild(EntitySpec.create(TestEntity.class)
                    .location(machine));
            Entities.manage(subnetTier);
            app.start(ImmutableList.of(loc));
            
            entity.setAttribute(PRIVATE_PORT, 1234);
            subnetTier.openPortForwardingAndAdvertise(
                    EntityAndAttribute.supplier(entity, PRIVATE_PORT), 
                    Optional.of(expectedPort),
                    Protocol.TCP,
                    Cidr.UNIVERSAL,
                    EntityAndAttribute.supplier(entity, MAPPED_ENDPOINT));
            
            Asserts.succeedsEventually(new Runnable() {
                public void run() {
                    assertEquals(entity.getAttribute(MAPPED_ENDPOINT), expectedEndpoint);
                }});
        } finally {
            // TODO Delete DNAT rule
        }
    }
}
