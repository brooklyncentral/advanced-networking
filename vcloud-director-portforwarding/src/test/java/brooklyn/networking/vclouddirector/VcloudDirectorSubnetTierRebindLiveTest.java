package brooklyn.networking.vclouddirector;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.rebind.RebindTestFixtureWithApp;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.Sensors;
import brooklyn.location.LocationSpec;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.networking.subnet.SubnetTier;
import brooklyn.test.Asserts;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

public class VcloudDirectorSubnetTierRebindLiveTest extends RebindTestFixtureWithApp {

    private static final String LOCATION_SPEC = "canopy-vCHS";
    public static final String EXISTING_NETWORK_NAME = "M523007043-2739-default-routed";
    public static final String AVAILABLE_PUBLIC_IP = "23.92.230.8";
    public static final String EXPECTED_ENDPOINT = "23.92.230.8:5678";

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
                EntityAndAttribute.supplier(origEntity, PRIVATE_PORT), 
                Optional.of(5678),
                Protocol.TCP,
                Cidr.UNIVERSAL,
                EntityAndAttribute.supplier(origEntity, MAPPED_ENDPOINT));

        // Confirm that subnet tier (and port forwarding calls) still work
        origEntity.setAttribute(PRIVATE_PORT, 1234);
        
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                assertEquals(origEntity.getAttribute(MAPPED_ENDPOINT), EXPECTED_ENDPOINT);
            }});
        
        // rebind
        rebind();
        SubnetTier newSubnetTier = (SubnetTier) Iterables.find(newApp.getChildren(), Predicates.instanceOf(SubnetTier.class));
        TestEntity newEntity = (TestEntity) Iterables.find(newSubnetTier.getChildren(), Predicates.instanceOf(TestEntity.class));

        assertEquals(newEntity.getAttribute(MAPPED_ENDPOINT), EXPECTED_ENDPOINT);
    }

    // FIXME Fails with newEntity not getting its expected mapped_endpoint set
    @Test(groups={"Live", "WIP"})
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
                EntityAndAttribute.supplier(origEntity, PRIVATE_PORT), 
                Optional.of(5678),
                Protocol.TCP,
                Cidr.UNIVERSAL,
                EntityAndAttribute.supplier(origEntity, MAPPED_ENDPOINT));

        // rebind
        rebind();
        SubnetTier newSubnetTier = (SubnetTier) Iterables.find(newApp.getChildren(), Predicates.instanceOf(SubnetTier.class));
        final TestEntity newEntity = (TestEntity) Iterables.find(newSubnetTier.getChildren(), Predicates.instanceOf(TestEntity.class));
        
        // Confirm that the port-forwarding registered pre-rebind picks up the change.
        newEntity.setAttribute(PRIVATE_PORT, 1234);
        
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                assertEquals(newEntity.getAttribute(MAPPED_ENDPOINT), EXPECTED_ENDPOINT);
            }});
    }

    // FIXME Fails with newEntity not getting its expected mapped_endpoint set
    @Test(groups={"Live", "WIP"})
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
                EntityAndAttribute.supplier(origEntity, PRIVATE_PORT), 
                Optional.of(5678),
                Protocol.TCP,
                Cidr.UNIVERSAL,
                EntityAndAttribute.supplier(origEntity, MAPPED_ENDPOINT));

        newEntity.setAttribute(PRIVATE_PORT, 1234);
        
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                assertEquals(newEntity.getAttribute(MAPPED_ENDPOINT), EXPECTED_ENDPOINT);
            }});
    }
}
