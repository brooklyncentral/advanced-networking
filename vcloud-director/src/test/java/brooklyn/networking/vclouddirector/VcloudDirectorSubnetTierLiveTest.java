package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.BrooklynAppLiveTestSupport;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.machine.MachineEntity;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.Sensors;
import brooklyn.location.LocationSpec;
import brooklyn.location.basic.Machines;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.networking.portforwarding.subnet.SubnetTier;
import brooklyn.test.EntityTestUtils;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

/**
 * See {@link NatServiceLiveTest} for details of environment setup assumptions. 
 */
public class VcloudDirectorSubnetTierLiveTest extends BrooklynAppLiveTestSupport {

    // TODO Also need to improve and test error handling (e.g. the port is already assigned).
    
    private static final String LOCATION_SPEC = "canopy-vCHS";

    /**
     * A valid looking address for inside `canopy-vCHS`. There doesn't need to be any VM
     * with this name.
     */
    public static final String INTERNAL_MACHINE_IP = "192.168.109.10";
    
    protected JcloudsLocation provisioningLoc;

    private String publicIp;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        provisioningLoc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_SPEC);
        publicIp = (String) checkNotNull(provisioningLoc.getAllConfigBag().getStringKey("advancednetworking.vcloud.network.publicip"), "publicip");
    }
    
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }
    
    @Test(groups="Live")
    public void testOpenPortForwardingAndAdvertise() throws Exception {
        final AttributeSensor<Integer> PRIVATE_PORT = Sensors.newIntegerSensor("mapped.port");
        final AttributeSensor<String> MAPPED_ENDPOINT = Sensors.newStringSensor("mapped.endpoint");
        
        final int expectedPort = 45678;
        final String expectedEndpoint = publicIp +":"+expectedPort;

        SubnetTier subnetTier = app.addChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER, new PortForwarderVcloudDirector())
                .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, publicIp));
        final Entity entity = subnetTier.addChild(EntitySpec.create(MachineEntity.class));
        try {
            Entities.manage(subnetTier);
            app.start(ImmutableList.of(provisioningLoc));
            
            ((EntityLocal) entity).setAttribute(PRIVATE_PORT, 22);
            subnetTier.openPortForwardingAndAdvertise(
                    EntityAndAttribute.supplier(entity, PRIVATE_PORT),
                    Optional.of(expectedPort),
                    Protocol.TCP,
                    Cidr.UNIVERSAL,
                    EntityAndAttribute.supplier(entity, MAPPED_ENDPOINT));

            EntityTestUtils.assertAttributeEqualsEventually(entity, MAPPED_ENDPOINT, expectedEndpoint);
            
            SshMachineLocation machine = Machines.findUniqueSshMachineLocation(entity.getLocations()).get();
            SshMachineLocation machineViaOtherPort = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                    .configure("user", machine.getUser())
                    .configure("address", publicIp)
                    .configure("port", expectedPort)
                    .configure("privateKeyFile", machine.getConfig(SshMachineLocation.PRIVATE_KEY_FILE))
                    .configure("privateKeyData", machine.getConfig(SshMachineLocation.PRIVATE_KEY_DATA))
                    .configure("password", machine.getConfig(SshMachineLocation.PASSWORD)));
            assertTrue(machine.isSshable(), "machine="+machine);
            assertTrue(machineViaOtherPort.isSshable(), "machine="+machineViaOtherPort);
            
        } finally {
            ((PortForwarderVcloudDirector) subnetTier.getPortForwarder()).closePortForwarding(EntityAndAttribute.supplier(entity, PRIVATE_PORT), expectedPort);
        }
    }
    
    /**
     * This is a duplicate of {@link #testOpenPortForwardingAndAdvertise}, but it does not provision a VM
     * so is much faster to run.
     */
    @Test(groups="Live")
    public void testOpenPortForwardingAndAdvertiseWithoutCreatingVms() throws Exception {
        final AttributeSensor<Integer> PRIVATE_PORT = Sensors.newIntegerSensor("mapped.port");
        final AttributeSensor<String> MAPPED_ENDPOINT = Sensors.newStringSensor("mapped.endpoint");
        
        final int expectedPort = 45678;
        final String expectedEndpoint = publicIp +":"+expectedPort;

        SshMachineLocation pseudoMachineLoc = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("user", "myuser")
                .configure("address", INTERNAL_MACHINE_IP));
        
        SubnetTier subnetTier = app.addChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER, new PortForwarderVcloudDirector())
                .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, publicIp));
        final Entity entity = subnetTier.addChild(EntitySpec.create(TestEntity.class)
                .location(pseudoMachineLoc));
        try {
            Entities.manage(subnetTier);
            app.start(ImmutableList.of(provisioningLoc));
            
            ((EntityLocal) entity).setAttribute(PRIVATE_PORT, 22);
            subnetTier.openPortForwardingAndAdvertise(
                    EntityAndAttribute.supplier(entity, PRIVATE_PORT),
                    Optional.of(expectedPort),
                    Protocol.TCP,
                    Cidr.UNIVERSAL,
                    EntityAndAttribute.supplier(entity, MAPPED_ENDPOINT));

            EntityTestUtils.assertAttributeEqualsEventually(entity, MAPPED_ENDPOINT, expectedEndpoint);
        } finally {
            ((PortForwarderVcloudDirector) subnetTier.getPortForwarder()).closePortForwarding(EntityAndAttribute.supplier(entity, PRIVATE_PORT), expectedPort);
        }
    }
}
