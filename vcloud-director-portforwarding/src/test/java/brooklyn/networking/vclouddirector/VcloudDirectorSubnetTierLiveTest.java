package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.BrooklynAppLiveTestSupport;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractEntity;
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
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.google.common.net.HostAndPort;
import com.vmware.vcloud.api.rest.schema.NatRuleType;

/**
 * See {@link NatServiceLiveTest} for details of environment setup assumptions. 
 */
public class VcloudDirectorSubnetTierLiveTest extends BrooklynAppLiveTestSupport {

    // TODO Also need to improve and test error handling (e.g. the port is already assigned).

    private static final Logger LOG = LoggerFactory.getLogger(AbstractEntity.class);

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
               // Don't use NatMicroServiceMain.main directly, because that will do System.exit at the end
               try {
                   Callable<?> command = new NatMicroServiceMain().cliBuilder().build().parse(
                           "launch", "--endpointsProperties", endpointsPropertiesFile.getAbsolutePath());
                   command.call();
               } catch (Exception e) {
                   LOG.error("Launch NAT micro-service failed", e);
                   throw Exceptions.propagate(e);
               }
           }});
        
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
        final AttributeSensor<Integer> PRIVATE_PORT = Sensors.newIntegerSensor("my.port");
        final AttributeSensor<String> MAPPED_ENDPOINT = Sensors.newStringSensor("mapped.endpoint");
        
        final int expectedPort = 45678;
        final String expectedEndpoint = publicIp +":"+expectedPort;

        SubnetTier subnetTier = app.addChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER, new PortForwarderVcloudDirector())
                .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, publicIp)
                .configure(PortForwarderVcloudDirector.NAT_MICROSERVICE_ENDPOINT, microserviceUrl));
        final MachineEntity entity = subnetTier.addChild(EntitySpec.create(MachineEntity.class));
        HostAndPort privateHostAndPort = null;
        
        Exception tothrow = null;
        try {
            Entities.manage(subnetTier);
            app.start(ImmutableList.of(loc));

            SshMachineLocation machine = Machines.findUniqueSshMachineLocation(entity.getLocations()).get();
            privateHostAndPort = HostAndPort.fromParts(
                    Iterables.get(Iterables.concat(machine.getPrivateAddresses(), machine.getPublicAddresses()), 0),
                    22);

            // Setup port-forwarding to port 22
            ((EntityLocal) entity).setAttribute(PRIVATE_PORT, 22);
            subnetTier.openPortForwardingAndAdvertise(
                    EntityAndAttribute.create(entity, PRIVATE_PORT),
                    Optional.of(expectedPort),
                    Protocol.TCP,
                    Cidr.UNIVERSAL,
                    EntityAndAttribute.create(entity, MAPPED_ENDPOINT));

            // Confirm the expected port is advertised
            EntityTestUtils.assertAttributeEqualsEventually(entity, MAPPED_ENDPOINT, expectedEndpoint);
            
            // Confirm can ssh to the VM via our newly mapped port
            SshMachineLocation machineViaOtherPort = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                    .configure("user", machine.getUser())
                    .configure("address", publicIp)
                    .configure("port", expectedPort)
                    .configure("privateKeyFile", machine.getConfig(SshMachineLocation.PRIVATE_KEY_FILE))
                    .configure("privateKeyData", machine.getConfig(SshMachineLocation.PRIVATE_KEY_DATA))
                    .configure("password", machine.getConfig(SshMachineLocation.PASSWORD)));
            assertTrue(machine.isSshable(), "machine="+machine);
            assertTrue(machineViaOtherPort.isSshable(), "machine="+machineViaOtherPort);
            
            // Stop the entity; expect it to close the port-forwarding
            entity.stop();
            List<NatRuleType> natRules = new NatDirectClient(loc).getClient().getNatRules();
            Optional<NatRuleType> rule = Iterables.tryFind(natRules, Predicates.<NatRuleType>and(
                    NatPredicates.translatedTargetEquals(Iterables.get(machine.getPrivateAddresses(), 0), 22),
                    NatPredicates.originalTargetEquals(publicIp, expectedPort),
                    NatPredicates.protocolMatches(Protocol.TCP)));
            assertFalse(rule.isPresent(), "rule="+rule);
            
        } catch (Exception e) {
            tothrow = e;
        } finally {
            // Just in case stop didn't delete it, do it here
            try {
                if (Machines.findUniqueMachineLocation(entity.getLocations()).isPresent()) {
                    ((PortForwarderVcloudDirector) subnetTier.getPortForwarder()).closePortForwarding(EntityAndAttribute.create(entity, PRIVATE_PORT), expectedPort);
                } else if (privateHostAndPort != null) {
                    new NatDirectClient(loc).closePortForwarding(new PortForwardingConfig()
                            .publicIp(publicIp)
                            .publicPort(expectedPort)
                            .target(privateHostAndPort)
                            .protocol(Protocol.TCP));
                }
            } catch (Exception e) {
                if (tothrow != null) {
                    LOG.warn("Problem closing port-forwarding in finally block for "+entity+", public port "+expectedPort+"; not propagating as will throw other exception from test", e);
                } else {
                    tothrow = e;
                }
            }
        }
        
        if (tothrow != null) throw tothrow;
    }
    
    /**
     * This is a duplicate of {@link #testOpenPortForwardingAndAdvertise}, but it does not provision a VM
     * so is much faster to run.
     */
    @Test(groups={"Live", "Live-sanity"})
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
            
            // Setup port-forwarding to port 22
            ((EntityLocal) entity).setAttribute(PRIVATE_PORT, 22);
            subnetTier.openPortForwardingAndAdvertise(
                    EntityAndAttribute.create(entity, PRIVATE_PORT),
                    Optional.of(expectedPort),
                    Protocol.TCP,
                    Cidr.UNIVERSAL,
                    EntityAndAttribute.create(entity, MAPPED_ENDPOINT));

            // Confirm the expected port is advertised
            EntityTestUtils.assertAttributeEqualsEventually(entity, MAPPED_ENDPOINT, expectedEndpoint);
            
            // Confirm the port-mapping exists
            List<NatRuleType> natRules = new NatDirectClient(loc).getClient().getNatRules();
            Optional<NatRuleType> rule = Iterables.tryFind(natRules, Predicates.<NatRuleType>and(
                    NatPredicates.translatedTargetEquals(INTERNAL_MACHINE_IP, 22),
                    NatPredicates.originalTargetEquals(publicIp, expectedPort),
                    NatPredicates.protocolMatches(Protocol.TCP)));
            assertTrue(rule.isPresent(), "rules="+natRules);

        } finally {
            ((PortForwarderVcloudDirector) subnetTier.getPortForwarder()).closePortForwarding(EntityAndAttribute.create(entity, PRIVATE_PORT), expectedPort);
        }
    }
}
