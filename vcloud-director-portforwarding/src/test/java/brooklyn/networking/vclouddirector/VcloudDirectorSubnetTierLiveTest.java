package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
import brooklyn.networking.subnet.SubnetTier;
import brooklyn.networking.vclouddirector.natmicroservice.NatMicroServiceMain;
import brooklyn.test.Asserts;
import brooklyn.test.EntityTestUtils;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;

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
    
    protected JcloudsLocation loc;
    
    private String publicIp;
    private String microserviceUrl;
    private ExecutorService executor;
    private File endpointsPropertiesFile;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        NatMicroServiceMain.StaticRefs.service = null;
        
        super.setUp();
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_SPEC);
        publicIp = (String) checkNotNull(loc.getAllConfigBag().getStringKey("advancednetworking.vcloud.network.publicip"), "publicip");
        
        // Create the NAT Micro-service
        String endpointsProperties = "my-vcloud.endpoint="+NatDirectClient.transformEndpoint(loc.getEndpoint()) + "\n"
                + "my-vcloud.trustStore=\n"
                + "my-vcloud.trustStorePassword=\n";
        endpointsPropertiesFile = File.createTempFile("endpoints", "properties");
        Files.write(endpointsProperties.getBytes(), endpointsPropertiesFile);
        
        executor = Executors.newCachedThreadPool();
        executor.submit(new Runnable() {
           public void run() {
//               Callable<?> command = new NatMicroServiceMain().cliBuilder().build().parse("help");
//               command.call();

               NatMicroServiceMain.main("launch", "--endpointsProperties", endpointsPropertiesFile.getAbsolutePath());
           }
        });
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                assertNotNull(NatMicroServiceMain.StaticRefs.service);
            }});
        microserviceUrl = NatMicroServiceMain.StaticRefs.service.getRootUrl();
    }
    
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        if (endpointsPropertiesFile != null) endpointsPropertiesFile.delete();
        if (NatMicroServiceMain.StaticRefs.service != null) {
            NatMicroServiceMain.StaticRefs.service.stop();
            NatMicroServiceMain.StaticRefs.service = null;
        }
        if (executor != null) executor.shutdownNow();
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
                .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, publicIp)
                .configure(PortForwarderVcloudDirector.NAT_MICROSERVICE_ENDPOINT, microserviceUrl));
        final Entity entity = subnetTier.addChild(EntitySpec.create(MachineEntity.class));
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
                .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, publicIp)
                .configure(PortForwarderVcloudDirector.NAT_MICROSERVICE_ENDPOINT, microserviceUrl));

        final Entity entity = subnetTier.addChild(EntitySpec.create(TestEntity.class)
                .location(pseudoMachineLoc));
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
            ((PortForwarderVcloudDirector) subnetTier.getPortForwarder()).closePortForwarding(EntityAndAttribute.supplier(entity, PRIVATE_PORT), expectedPort);
        }
    }
}
