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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
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

import com.vmware.vcloud.api.rest.schema.NatRuleType;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.Machines;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.entity.machine.MachineEntity;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.test.EntityTestUtils;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Protocol;

import brooklyn.networking.subnet.SubnetTier;
import brooklyn.networking.vclouddirector.natmicroservice.NatMicroServiceMain;

/**
 * See {@link NatServiceLiveTest} for details of environment setup assumptions. 
 */
public class VcloudDirectorSubnetTierLiveTest extends BrooklynAppLiveTestSupport {

    // TODO Also need to improve and test error handling (e.g. the port is already assigned).

    private static final Logger LOG = LoggerFactory.getLogger(VcloudDirectorSubnetTierLiveTest.class);

    private static final String LOCATION_SPEC = "canopy-vCHS";

    /**
     * A valid looking address for inside `canopy-vCHS`. There doesn't need to be any VM
     * with this name.
     */
    public static final String INTERNAL_MACHINE_IP = "192.168.109.10";
    
    public static final int STARTING_PORT = 19980;
    public static final int ENDING_PORT = 19999;
    public static final PortRange DEFAULT_PORT_RANGE = PortRanges.fromString(STARTING_PORT+"-"+ENDING_PORT);
    
    protected JcloudsLocation loc;
    
    private String publicIp;
    private ExecutorService executor;
    private File endpointsPropertiesFile;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        NatMicroServiceMain.StaticRefs.service = null;
        
        super.setUp();
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_SPEC);
        publicIp = checkNotNull(loc.getConfig(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP), "publicip");
        executor = Executors.newCachedThreadPool();
    }
    
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        if (NatMicroServiceMain.StaticRefs.service != null) {
            NatMicroServiceMain.StaticRefs.service.stop();
            NatMicroServiceMain.StaticRefs.service = null;
        }
        if (endpointsPropertiesFile != null) endpointsPropertiesFile.delete();
        if (executor != null) executor.shutdownNow();
        super.tearDown();
    }

    // Disabled by default: do *not* run at times when production vCD NAT Microservice might be in-use
    @Test(groups="Live", enabled=false)
    public void testOpenPortForwardingAndAdvertise() throws Exception {
        // TODO When production vCD NAT Microservice is upgraded to support this, then just connect to that.
        String microserviceUrl = startVcdNatMicroservice();
        mgmt.getBrooklynProperties().put(PortForwarderVcloudDirector.NAT_MICROSERVICE_ENDPOINT, microserviceUrl);
        mgmt.getBrooklynProperties().put(PortForwarderVcloudDirector.NAT_MICROSERVICE_AUTO_ALLOCATES_PORT, "true");
        
        runOpenPortForwardingAndAdvertise(null, true);
    }
    
    @Test(groups="Live")
    public void testOpenPortForwardingAndAdvertiseWithExplicitPublicPort() throws Exception {
        String microserviceUrl = mgmt.getConfig().getConfig(PortForwarderVcloudDirector.NAT_MICROSERVICE_ENDPOINT);
        assertNotNull(microserviceUrl);
        
        runOpenPortForwardingAndAdvertise(STARTING_PORT+5, true);
    }
    
    // Disabled by default: do *not* run at times when production vCD NAT Microservice might be in-use
    @Test(groups={"Live", "Live-sanity"}, enabled=false)
    public void testOpenPortForwardingAndAdvertiseWithoutCreatingVms() throws Exception {
        // TODO When production vCD NAT Microservice is upgraded to support this, then just connect to that.
        String microserviceUrl = startVcdNatMicroservice();
        mgmt.getBrooklynProperties().put(PortForwarderVcloudDirector.NAT_MICROSERVICE_ENDPOINT, microserviceUrl);
        mgmt.getBrooklynProperties().put(PortForwarderVcloudDirector.NAT_MICROSERVICE_AUTO_ALLOCATES_PORT, "true");
        
        runOpenPortForwardingAndAdvertise(null, false);
    }
    
    // Disabled by default: do *not* run at times when production vCD NAT Microservice might be in-use
    @Test(groups={"Live", "Live-sanity"}, enabled=false)
    public void testOpenPortForwardingAndAdvertiseUsingPortRangeOnLocationWithoutCreatingVms() throws Exception {
        // TODO When production vCD NAT Microservice is upgraded to support this, then just connect to that.
        String microserviceUrl = startVcdNatMicroservice();
        mgmt.getBrooklynProperties().put(PortForwarderVcloudDirector.NAT_MICROSERVICE_ENDPOINT, microserviceUrl);
        mgmt.getBrooklynProperties().put(PortForwarderVcloudDirector.NAT_MICROSERVICE_AUTO_ALLOCATES_PORT, "true");
        mgmt.getBrooklynProperties().put("brooklyn.location.named."+LOCATION_SPEC+"."+PortForwarderVcloudDirector.PORT_RANGE.getName(),
                (STARTING_PORT+5)+"-"+ENDING_PORT);

        // Replace the original loc, now that we've updated the port-range
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_SPEC);

        HostAndPort publicEndpoint = runOpenPortForwardingAndAdvertise(null, false);
        
        assertTrue(publicEndpoint.getPort() >= (STARTING_PORT+5), "publicEndpoint="+publicEndpoint);
    }
    
    @Test(groups={"Live", "Live-sanity"})
    public void testOpenPortForwardingAndAdvertiseWithoutCreatingVmsWithExplicitPublicPort() throws Exception {
        String microserviceUrl = startVcdNatMicroservice();
        mgmt.getBrooklynProperties().put(PortForwarderVcloudDirector.NAT_MICROSERVICE_ENDPOINT, microserviceUrl);

        runOpenPortForwardingAndAdvertise(null, false);
        runOpenPortForwardingAndAdvertise(STARTING_PORT+5, false);
    }
    
    /**
     * With {@code createVm=false}, it does not provision a VM is much faster to run. However, it therefore
     * can't confirm the port-mapping is actually effective.
     * 
     * @return The public endpoint
     */
    protected HostAndPort runOpenPortForwardingAndAdvertise(Integer publicPort, boolean createVm) throws Exception {
        final AttributeSensor<Integer> PRIVATE_PORT = Sensors.newIntegerSensor("my.port");
        final AttributeSensor<String> MAPPED_ENDPOINT = Sensors.newStringSensor("mapped.endpoint");
        
        SubnetTier subnetTier = app.addChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER, new PortForwarderVcloudDirector())
                .configure(PortForwarderVcloudDirector.NETWORK_PUBLIC_IP, publicIp));
        
        final Entity entity;
        if (createVm) {
            entity = subnetTier.addChild(EntitySpec.create(MachineEntity.class));
        } else {
            SshMachineLocation pseudoMachineLoc = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                    .configure("user", "myuser")
                    .configure("address", INTERNAL_MACHINE_IP));
            
            entity = subnetTier.addChild(EntitySpec.create(TestEntity.class)
                    .location(pseudoMachineLoc));
        }
        
        HostAndPort targetEndpoint = null;
        HostAndPort publicEndpoint = null;
        
        Exception tothrow = null;
        try {
            Entities.manage(subnetTier);
            app.start(ImmutableList.of(loc));

            SshMachineLocation machine = Machines.findUniqueSshMachineLocation(entity.getLocations()).get();
            targetEndpoint = HostAndPort.fromParts(
                    Iterables.get(Iterables.concat(machine.getPrivateAddresses(), machine.getPublicAddresses()), 0),
                    22);

            // Setup port-forwarding to port 22
            ((EntityLocal) entity).setAttribute(PRIVATE_PORT, 22);
            subnetTier.openPortForwardingAndAdvertise(
                    EntityAndAttribute.create(entity, PRIVATE_PORT),
                    (publicPort == null) ? Optional.<Integer>absent() : Optional.of(publicPort),
                    Protocol.TCP,
                    Cidr.UNIVERSAL,
                    EntityAndAttribute.create(entity, MAPPED_ENDPOINT));

            // Confirm the expected port is advertised
            if (publicPort != null) {
                publicEndpoint = HostAndPort.fromParts(publicIp, publicPort);
                EntityTestUtils.assertAttributeEqualsEventually(entity, MAPPED_ENDPOINT, publicEndpoint.toString());
            } else {
                String mappedEndpoint = EntityTestUtils.assertAttributeEventuallyNonNull(entity, MAPPED_ENDPOINT);
                publicEndpoint = HostAndPort.fromString(mappedEndpoint);
                assertEquals(publicEndpoint.getHostText(), publicIp);
                assertTrue(publicEndpoint.hasPort(), "result="+publicEndpoint);
                assertTrue(contains(DEFAULT_PORT_RANGE, publicEndpoint.getPort()), "result="+publicEndpoint);
            }

            // Confirm the NAT Rule exists
            assertRuleExists(publicEndpoint, targetEndpoint, Protocol.TCP);

            // Confirm can ssh to the VM via our newly mapped port
            if (createVm) {
                SshMachineLocation machineViaOtherPort = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                        .configure("user", machine.getUser())
                        .configure("address", publicEndpoint.getHostText())
                        .configure("port", publicEndpoint.getPort())
                        .configure("privateKeyFile", machine.getConfig(SshMachineLocation.PRIVATE_KEY_FILE))
                        .configure("privateKeyData", machine.getConfig(SshMachineLocation.PRIVATE_KEY_DATA))
                        .configure("password", machine.getConfig(SshMachineLocation.PASSWORD)));
                assertTrue(machine.isSshable(), "machine="+machine);
                assertTrue(machineViaOtherPort.isSshable(), "machine="+machineViaOtherPort);
            }
            
            // Stop the entity; expect it to close the port-forwarding
            ((Startable)entity).stop();

            // TODO Want the pseudo-machine code path to also use MachineEntity; 
            // but fails to start if can't connect over ssh
            if (createVm) {
                assertRuleNotExists(publicEndpoint, targetEndpoint, Protocol.TCP);
            }
            
        } catch (Exception e) {
            tothrow = e;
        } finally {
            // Just in case stop didn't delete it, do it here
            Integer actualPublicPort = (publicPort != null) ? publicPort : (publicEndpoint != null) ? publicEndpoint.getPort() : null;
            if (actualPublicPort != null) {
                try {
                    if (Machines.findUniqueMachineLocation(entity.getLocations()).isPresent()) {
                        ((PortForwarderVcloudDirector) subnetTier.getPortForwarder()).closePortForwarding(EntityAndAttribute.create(entity, PRIVATE_PORT), actualPublicPort);
                    } else if (targetEndpoint != null) {
                        String microserviceUrl = subnetTier.config().get(PortForwarderVcloudDirector.NAT_MICROSERVICE_ENDPOINT);
                        if (microserviceUrl == null) {
                            microserviceUrl = mgmt.getConfig().getConfig(PortForwarderVcloudDirector.NAT_MICROSERVICE_ENDPOINT);
                        }

                        NatClient client = new NatMicroserviceClient(microserviceUrl, loc);
                        client.closePortForwarding(new PortForwardingConfig()
                                .publicEndpoint(HostAndPort.fromParts(publicIp, actualPublicPort))
                                .targetEndpoint(targetEndpoint)
                                .protocol(Protocol.TCP));
                    }
                } catch (Exception e) {
                    if (tothrow != null) {
                        LOG.error("Problem closing port-forwarding in finally block for "+entity+", public port "+actualPublicPort+"; not propagating as will throw other exception from test", e);
                    } else {
                        tothrow = e;
                    }
                }
            }
        }
        
        if (tothrow != null) throw tothrow;
        return publicEndpoint;
    }

    protected String startVcdNatMicroservice() throws Exception {
        assertNull(NatMicroServiceMain.StaticRefs.service);
        
        // Create the NAT Micro-service
        String endpointsProperties = "my-vcloud.endpoint="+NatDirectClient.transformEndpoint(loc.getEndpoint()) + "\n"
                + "my-vcloud.trustStore=\n"
                + "my-vcloud.trustStorePassword=\n"
                + String.format("my-vcloud.portRange=%s+\n", "12000");
        endpointsPropertiesFile = File.createTempFile("endpoints", "properties");
        Files.write(endpointsProperties.getBytes(), endpointsPropertiesFile);
        
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
         return NatMicroServiceMain.StaticRefs.service.getRootUrl();
    }
    
    protected boolean contains(PortRange range, int port) {
        for (int contender : range) {
            if (contender == port) return true;
        }
        return false;
    }
    
    protected void assertRuleExists(HostAndPort publicEndpoint, HostAndPort targetEndpoint, Protocol protocol) throws Exception {
        Optional<NatRuleType> rule = tryFindRule(publicEndpoint, targetEndpoint, protocol);
        assertNotNull(rule.get(), "rule="+rule);
    }

    protected void assertRuleNotExists(HostAndPort publicEndpoint, HostAndPort targetEndpoint, Protocol protocol) throws Exception {
        Optional<NatRuleType> rule = tryFindRule(publicEndpoint, targetEndpoint, protocol);
        assertFalse(rule.isPresent(), "rule="+rule);
    }

    protected Optional<NatRuleType> tryFindRule(HostAndPort publicEndpoint, HostAndPort targetEndpoint, Protocol protocol) throws Exception {
        List<NatRuleType> natRules = new NatDirectClient(loc).getClient().getNatRules();
        Optional<NatRuleType> rule = Iterables.tryFind(natRules, Predicates.<NatRuleType>and(
                NatPredicates.translatedEndpointEquals(targetEndpoint),
                NatPredicates.originalEndpointEquals(publicEndpoint),
                NatPredicates.protocolMatches(protocol)));
        return rule;
    }
}
