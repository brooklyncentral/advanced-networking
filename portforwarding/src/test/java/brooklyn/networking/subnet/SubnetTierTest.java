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
package brooklyn.networking.subnet;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.test.EntityTestUtils;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.HasNetworkAddresses;
import org.apache.brooklyn.util.net.Networking;
import org.apache.brooklyn.util.net.Protocol;
import org.apache.brooklyn.util.time.Duration;

import brooklyn.networking.common.subnet.PortForwarder;
import brooklyn.networking.portforwarding.NoopPortForwarder;

public class SubnetTierTest {

    private static final Logger log = LoggerFactory.getLogger(SubnetTierTest.class);

    private Map<HostAndPort, HostAndPort> portMapping;
    private String machineAddress = "1.2.3.4";
    private TestApplication app;
    private SubnetTier subnetTier;
    private TestEntity entity;
    private ManagementContext managementContext;
    private SshMachineLocation simulatedMachine;

    private PortForwardManager portForwardManager;

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        app = TestApplication.Factory.newManagedInstanceForTests();
        managementContext = app.getManagementContext();
        portForwardManager = (PortForwardManager) managementContext.getLocationRegistry().resolve("portForwardManager(scope=global)");
        
        portMapping = Maps.newLinkedHashMap();
        PortForwarder portForwarder = new StubPortForwarder(portMapping);

        subnetTier = app.createAndManageChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER, portForwarder)
                .configure(SubnetTier.PORT_FORWARDING_MANAGER, portForwardManager));
        entity = subnetTier.addChild(EntitySpec.create(TestEntity.class));
        Entities.manage(entity);

        simulatedMachine = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", Networking.getInetAddressWithFixedName(machineAddress))
                .configure("port", 1234)
                .configure("user", "myuser"));
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (app != null) Entities.destroyAll(app.getManagementContext());
    }

    @Test
    public void testTransformSensorStringReplacingWithPublicAddressAndPort() throws Exception {
        final AttributeSensor<Integer> TARGET_PORT = new BasicAttributeSensor<Integer>(Integer.class, "target.port");
        final AttributeSensor<String> ENDPOINT = new BasicAttributeSensor<String>(String.class, "endpoint");
        final AttributeSensor<String> PUBLIC_ENDPOINT = new BasicAttributeSensor<String>(String.class, "publicEndpoint");

        subnetTier.transformSensorStringReplacingWithPublicAddressAndPort(
                new EntityAndAttribute<String>(app, ENDPOINT),
                Optional.of(new EntityAndAttribute<Integer>(app, TARGET_PORT)),
                new EntityAndAttribute<String>(app, PUBLIC_ENDPOINT));

        ((EntityLocal)app).setAttribute(Attributes.HOSTNAME, "myprivatehostname");
        ((EntityLocal)app).setAttribute(TARGET_PORT, 1234);
        ((EntityLocal)app).setAttribute(ENDPOINT, "PREFIX://myprivatehostname:1234/POSTFIX");
        ((EntityLocal)app).setAttribute(PUBLIC_ENDPOINT, "mypublichostname:5678");

        log.info(app.getAttribute(ENDPOINT));

        EntityTestUtils.assertAttributeEqualsEventually(app, ENDPOINT, "PREFIX://mypublichostname:5678/POSTFIX");
    }

    @Test
    public void testHostAndPortTransformingEnricher() throws Exception {
        final AttributeSensor<Integer> TARGET_PORT = new BasicAttributeSensor<Integer>(Integer.class, "target.port");
        final AttributeSensor<String> PUBLIC_ENDPOINT = new BasicAttributeSensor<String>(String.class, "publicEndpoint");

        String publicIpId = "mypublicipid";
        String publicAddress = "5.6.7.8";
        portMapping.put(HostAndPort.fromParts(machineAddress, 80), HostAndPort.fromParts(publicAddress, 40080));
        portForwardManager.associate(publicIpId, HostAndPort.fromParts(publicAddress, 40080), simulatedMachine, 80);

        entity.addEnricher(subnetTier.hostAndPortTransformingEnricher(
                new EntityAndAttribute<Integer>(entity, TARGET_PORT),
                PUBLIC_ENDPOINT));

        entity.addLocations(ImmutableList.of(simulatedMachine));
        entity.setAttribute(TARGET_PORT, 80);

        EntityTestUtils.assertAttributeEqualsEventually(entity, PUBLIC_ENDPOINT, publicAddress+":"+40080);
    }

    @Test
    public void testHostAndPortTransformingEnricherWithDelayedPortMapping() throws Exception {
        final AttributeSensor<Integer> TARGET_PORT = new BasicAttributeSensor<Integer>(Integer.class, "target.port");
        final AttributeSensor<String> PUBLIC_ENDPOINT = new BasicAttributeSensor<String>(String.class, "publicEndpoint");

        String publicIpId = "mypublicipid";
        String publicAddress = "5.6.7.8";
        RecordingSensorEventListener sensorListener = new RecordingSensorEventListener();
        entity.subscribe(entity, PUBLIC_ENDPOINT, sensorListener);

        entity.addEnricher(subnetTier.hostAndPortTransformingEnricher(
                new EntityAndAttribute<Integer>(entity, TARGET_PORT),
                PUBLIC_ENDPOINT));

        entity.addLocations(ImmutableList.of(simulatedMachine));
        entity.setAttribute(TARGET_PORT, 80);

        // TODO It sets the sensor to null (!). We'll rely on that for now, but feels like it 
        // shouldn't bother setting it at all!
        sensorListener.assertEventEventually(Duration.THIRTY_SECONDS);

        portMapping.put(HostAndPort.fromParts(machineAddress, 80), HostAndPort.fromParts(publicAddress, 40080));
        portForwardManager.associate(publicIpId, HostAndPort.fromParts(publicAddress, 40080), simulatedMachine, 80);

        EntityTestUtils.assertAttributeEqualsEventually(entity, PUBLIC_ENDPOINT, publicAddress+":"+40080);
    }

    @Test
    public void testUriTransformingEnricher() throws Exception {
        final AttributeSensor<String> ENDPOINT = new BasicAttributeSensor<String>(String.class, "endpoint");
        final AttributeSensor<String> PUBLIC_ENDPOINT = new BasicAttributeSensor<String>(String.class, "publicEndpoint");

        String publicIpId = "mypublicipid";
        String publicAddress = "5.6.7.8";
        portMapping.put(HostAndPort.fromParts(machineAddress, 80), HostAndPort.fromParts(publicAddress, 40080));
        portForwardManager.associate(publicIpId, HostAndPort.fromParts(publicAddress, 40080), simulatedMachine, 80);

        entity.addEnricher(subnetTier.uriTransformingEnricher(ENDPOINT, PUBLIC_ENDPOINT));

        entity.addLocations(ImmutableList.of(simulatedMachine));
        entity.setAttribute(ENDPOINT, "http://"+machineAddress+":80");

        EntityTestUtils.assertAttributeEqualsEventually(entity, PUBLIC_ENDPOINT, "http://"+publicAddress+":"+40080);
    }

    @Test
    public void testUriTransformingEnricherWithSourceSetToHostAndPort() throws Exception {
        final AttributeSensor<String> ENDPOINT = new BasicAttributeSensor<String>(String.class, "endpoint");
        final AttributeSensor<String> PUBLIC_ENDPOINT = new BasicAttributeSensor<String>(String.class, "publicEndpoint");

        String publicIpId = "mypublicipid";
        String publicAddress = "5.6.7.8";
        portMapping.put(HostAndPort.fromParts(machineAddress, 80), HostAndPort.fromParts(publicAddress, 40080));
        portForwardManager.associate(publicIpId, HostAndPort.fromParts(publicAddress, 40080), simulatedMachine, 80);

        entity.addEnricher(subnetTier.uriTransformingEnricher(ENDPOINT, PUBLIC_ENDPOINT));

        entity.addLocations(ImmutableList.of(simulatedMachine));
        entity.setAttribute(ENDPOINT, machineAddress+":80");

        EntityTestUtils.assertAttributeEqualsEventually(entity, PUBLIC_ENDPOINT, publicAddress+":"+40080);
    }

    /**
     * Tests that the transformed URI is published, even if the port-mapping is only set after the 
     * transforming enricher has processed the original attribute-changed event.
     * 
     * Prior to the bug fix, there was no listener registered for new port-forwardings. Therefore the
     * enricher was never kicked off again so it never got the transformed URI.
     */
    @Test
    public void testUriTransformingEnricherWithDelayedPortMapping() throws Exception {
        final AttributeSensor<String> ENDPOINT = new BasicAttributeSensor<String>(String.class, "endpoint");
        final AttributeSensor<String> PUBLIC_ENDPOINT = new BasicAttributeSensor<String>(String.class, "publicEndpoint");

        String publicIpId = "mypublicipid";
        String publicAddress = "5.6.7.8";
        RecordingSensorEventListener sensorListener = new RecordingSensorEventListener();
        entity.subscribe(entity, PUBLIC_ENDPOINT, sensorListener);

        entity.addEnricher(subnetTier.uriTransformingEnricher(ENDPOINT, PUBLIC_ENDPOINT));

        entity.addLocations(ImmutableList.of(simulatedMachine));
        entity.setAttribute(ENDPOINT, "http://" + machineAddress + ":80");

        // TODO It sets the sensor to the original (unmapped) value. However, that is different
        // behaviour from transformPort or transformHostAndPortEnricher which both just set the
        // propagated sensor to null.
        sensorListener.assertEventEventually(Duration.THIRTY_SECONDS);

        portMapping.put(HostAndPort.fromParts(machineAddress, 80), HostAndPort.fromParts(publicAddress, 40080));
        portForwardManager.associate(publicIpId, HostAndPort.fromParts(publicAddress, 40080), simulatedMachine, 80);

        EntityTestUtils.assertAttributeEqualsEventually(entity, PUBLIC_ENDPOINT, "http://"+publicAddress+":"+40080);
    }

    @Test
    public void testTransformUri() throws Exception {
        final AttributeSensor<String> ENDPOINT = new BasicAttributeSensor<String>(String.class, "endpoint");

        String publicIpId = "mypublicipid";
        String publicAddress = "5.6.7.8";
        portMapping.put(HostAndPort.fromParts(machineAddress, 80), HostAndPort.fromParts(publicAddress, 40080));
        portForwardManager.associate(publicIpId, HostAndPort.fromParts(publicAddress, 40080), simulatedMachine, 80);

        subnetTier.transformUri(new EntityAndAttribute<String>(entity, ENDPOINT));

        entity.addLocations(ImmutableList.of(simulatedMachine));
        entity.setAttribute(ENDPOINT, "http://"+machineAddress+":80");

        EntityTestUtils.assertAttributeEqualsEventually(entity, ENDPOINT, "http://"+publicAddress+":"+40080);
    }

    @Test
    public void testTransformUriPublishingElsewhere() throws Exception {
        final AttributeSensor<String> ENDPOINT = new BasicAttributeSensor<String>(String.class, "endpoint");
        final AttributeSensor<String> PUBLIC_ENDPOINT = new BasicAttributeSensor<String>(String.class, "mapped.endpoint");

        String publicIpId = "mypublicipid";
        String publicAddress = "5.6.7.8";
        portMapping.put(HostAndPort.fromParts(machineAddress, 80), HostAndPort.fromParts(publicAddress, 40080));
        portForwardManager.associate(publicIpId, HostAndPort.fromParts(publicAddress, 40080), simulatedMachine, 80);

        subnetTier.transformUri(new EntityAndAttribute<String>(entity, ENDPOINT), new EntityAndAttribute<String>(app, PUBLIC_ENDPOINT));

        entity.addLocations(ImmutableList.of(simulatedMachine));
        entity.setAttribute(ENDPOINT, "http://"+machineAddress+":80");

        EntityTestUtils.assertAttributeEqualsEventually(app, PUBLIC_ENDPOINT, "http://"+publicAddress+":"+40080);
    }

    @Test
    public void testTransformUriWithDelayedPortMapping() throws Exception {
        final AttributeSensor<String> ENDPOINT = new BasicAttributeSensor<String>(String.class, "endpoint");
        final AttributeSensor<String> PUBLIC_ENDPOINT = new BasicAttributeSensor<String>(String.class, "mapped.endpoint");

        String publicIpId = "mypublicipid";
        String publicAddress = "5.6.7.8";
        RecordingSensorEventListener sensorListener = new RecordingSensorEventListener();
        entity.subscribe(app, PUBLIC_ENDPOINT, sensorListener);

        subnetTier.transformUri(new EntityAndAttribute<String>(entity, ENDPOINT), new EntityAndAttribute<String>(app, PUBLIC_ENDPOINT));

        entity.addLocations(ImmutableList.of(simulatedMachine));
        entity.setAttribute(ENDPOINT, "http://"+machineAddress+":80");

        sensorListener.assertEventEventually(Duration.THIRTY_SECONDS);

        portMapping.put(HostAndPort.fromParts(machineAddress, 80), HostAndPort.fromParts(publicAddress, 40080));
        portForwardManager.associate(publicIpId, HostAndPort.fromParts(publicAddress, 40080), simulatedMachine, 80);

        EntityTestUtils.assertAttributeEqualsEventually(app, PUBLIC_ENDPOINT, "http://"+publicAddress+":"+40080);
    }

    @Test
    public void testTransformPort() throws Exception {
        final AttributeSensor<Integer> ENDPOINT = Sensors.newIntegerSensor("endpoint");
        final AttributeSensor<String> PUBLIC_ENDPOINT = Sensors.newStringSensor("mapped.endpoint");

        String publicIpId = "mypublicipid";
        String publicAddress = "5.6.7.8";
        portMapping.put(HostAndPort.fromParts(machineAddress, 80), HostAndPort.fromParts(publicAddress, 40080));
        portForwardManager.associate(publicIpId, HostAndPort.fromParts(publicAddress, 40080), simulatedMachine, 80);

        subnetTier.transformPort(EntityAndAttribute.create(entity, ENDPOINT), EntityAndAttribute.create(app, PUBLIC_ENDPOINT));

        entity.addLocations(ImmutableList.of(simulatedMachine));
        entity.setAttribute(ENDPOINT, 80);

        EntityTestUtils.assertAttributeEqualsEventually(app, PUBLIC_ENDPOINT, publicAddress+":"+40080);
    }

    @Test
    public void testTransformPortDelayedPortMapping() throws Exception {
        final AttributeSensor<Integer> ENDPOINT = Sensors.newIntegerSensor("endpoint");
        final AttributeSensor<String> PUBLIC_ENDPOINT = Sensors.newStringSensor("mapped.endpoint");

        String publicIpId = "mypublicipid";
        String publicAddress = "5.6.7.8";
        RecordingSensorEventListener sensorListener = new RecordingSensorEventListener();
        entity.subscribe(app, PUBLIC_ENDPOINT, sensorListener);

        subnetTier.transformPort(EntityAndAttribute.create(entity, ENDPOINT), EntityAndAttribute.create(app, PUBLIC_ENDPOINT));

        entity.addLocations(ImmutableList.of(simulatedMachine));
        entity.setAttribute(ENDPOINT, 80);
        
        // TODO It sets the sensor to null (!). We'll rely on that for now, but feels like it 
        // shouldn't bother setting it at all!
        sensorListener.assertEventEventually(Duration.THIRTY_SECONDS);

        portMapping.put(HostAndPort.fromParts(machineAddress, 80), HostAndPort.fromParts(publicAddress, 40080));
        portForwardManager.associate(publicIpId, HostAndPort.fromParts(publicAddress, 40080), simulatedMachine, 80);

        EntityTestUtils.assertAttributeEqualsEventually(app, PUBLIC_ENDPOINT, publicAddress+":"+40080);
    }

    @Test
    public void testConfigurePortForwarderType() throws Exception {
        SubnetTier subnetTier2 = app.createAndManageChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER_TYPE, NoopPortForwarder.class.getName()));

        assertEquals(subnetTier2.getAttribute(SubnetTierImpl.PORT_FORWARDER_LIVE).getClass(), NoopPortForwarder.class);
    }

    public static class RecordingSensorEventListener implements SensorEventListener<Object> {
        public final List<SensorEvent<Object>> events = Lists.newCopyOnWriteArrayList();
        
        @Override
        public void onEvent(SensorEvent<Object> event) {
            events.add(event);
        }
        
        public void assertEventEventually(Duration timeout) {
            Asserts.succeedsEventually(new Runnable() {
                @Override public void run() {
                    assertTrue(events.size() > 0);
                }});
        }
    }
    
    public static class StubPortForwarder implements PortForwarder {
        final Map<HostAndPort, HostAndPort> mapping;

        StubPortForwarder(Map<HostAndPort, HostAndPort> mapping) {
            this.mapping = mapping;
        }
        @Override public void injectManagementContext(ManagementContext managementContext) {
            // no-op
        }
        @Override public void inject(Entity owner, List<Location> locations) {
            // no-op
        }
        @Override public HostAndPort openPortForwarding(HasNetworkAddresses targetMachine, int targetPort, Optional<Integer> optionalPublicPort,
                Protocol protocol, Cidr accessingCidr) {
            String targetIp = Iterables.getFirst(Iterables.concat(targetMachine.getPrivateAddresses(), targetMachine.getPublicAddresses()), null);
            HostAndPort targetSide = HostAndPort.fromParts(targetIp, targetPort);
            return checkNotNull(mapping.get(targetSide), "no mapping for %s", targetSide);
        }
        @Override public HostAndPort openPortForwarding(HostAndPort targetSide, Optional<Integer> optionalPublicPort, Protocol protocol, Cidr accessingCidr) {
            return checkNotNull(mapping.get(targetSide), "no mapping for %s", targetSide);
        }
        @Override public boolean closePortForwarding(HostAndPort targetSide, HostAndPort publicSide, Protocol protocol) {
            return true;
        }
        @Override public boolean closePortForwarding(HasNetworkAddresses machine, int targetPort, HostAndPort publicSide, Protocol protocol) {
            return true;
        }
        @Override public void openFirewallPort(Entity entity, int port, Protocol protocol, Cidr accessingCidr) {
            throw new UnsupportedOperationException();
        }
        @Override public void openFirewallPortRange(Entity entity, PortRange portRange, Protocol protocol, Cidr accessingCidr) {
            throw new UnsupportedOperationException();
        }
        @Override public String openGateway() {
            throw new UnsupportedOperationException();
        }
        @Override public String openStaticNat(Entity serviceToOpen) {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean isClient() {
            return false;
        }
        @Override
        public PortForwardManager getPortForwardManager() {
            return null;
        }
    }
}
