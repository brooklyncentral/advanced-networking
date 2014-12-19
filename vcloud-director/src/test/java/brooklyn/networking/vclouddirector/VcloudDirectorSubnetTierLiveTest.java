package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.BrooklynAppLiveTestSupport;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.machine.MachineEntity;
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

    protected JcloudsLocation loc;

    private String publicIp;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_SPEC);
        publicIp = (String) checkNotNull(loc.getAllConfigBag().getStringKey("advancednetworking.vcloud.network.publicip"), "publicip");
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
        final String expectedEndpoint = publicIp +":"+expectedPort;

        SubnetTier subnetTier = app.addChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER, new PortForwarderVcloudDirector())
                .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, publicIp));
        final MachineEntity entity = subnetTier.addChild(EntitySpec.create(MachineEntity.class)
                .location(loc));
        try {
            Entities.manage(subnetTier);
            app.start(ImmutableList.of(loc));
            
            ((EntityLocal) entity).setAttribute(PRIVATE_PORT, 22);
            subnetTier.openPortForwardingAndAdvertise(
                    EntityAndAttribute.supplier(entity, PRIVATE_PORT),
                    Optional.of(expectedPort),
                    Protocol.TCP,
                    Cidr.UNIVERSAL,
                    EntityAndAttribute.supplier(entity, MAPPED_ENDPOINT));

            EntityTestUtils.assertAttributeEqualsEventually(entity, MAPPED_ENDPOINT, expectedEndpoint);
        } finally {
            ((PortForwarderVcloudDirector) subnetTier.getPortForwarder()).closePortForwarding(EntityAndAttribute.supplier(entity, PRIVATE_PORT), Optional.of(expectedPort));
        }
    }
    
    @Test(groups="Live")
    public void testOpenPortForwardingAndAdvertiseUsingNetworkId() throws Exception {
        final AttributeSensor<Integer> PRIVATE_PORT = Sensors.newIntegerSensor("mapped.port");
        final AttributeSensor<String> MAPPED_ENDPOINT = Sensors.newStringSensor("mapped.endpoint");
        
        final int expectedPort = 5678;
        final String expectedEndpoint = publicIp+":"+expectedPort;

        SubnetTier subnetTier = app.addChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER, new PortForwarderVcloudDirector())
                .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, publicIp));
        final MachineEntity entity = subnetTier.addChild(EntitySpec.create(MachineEntity.class)
                .location(loc));
        try {

            Entities.manage(subnetTier);
            app.start(ImmutableList.of(loc));

            ((EntityLocal) entity).setAttribute(PRIVATE_PORT, 22);
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
            ((PortForwarderVcloudDirector ) subnetTier.getPortForwarder()).closePortForwarding(EntityAndAttribute.supplier(entity, PRIVATE_PORT), Optional.of(expectedPort));
        }
    }
}
