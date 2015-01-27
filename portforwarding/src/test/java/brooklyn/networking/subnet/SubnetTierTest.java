/*
 * Copyright 2013-2014 by Cloudsoft Corporation Limited
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

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.location.Location;
import brooklyn.location.LocationSpec;
import brooklyn.location.PortRange;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.management.ManagementContext;
import brooklyn.networking.portforwarding.NoopPortForwarder;
import brooklyn.test.EntityTestUtils;
import brooklyn.test.entity.TestApplication;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.net.Cidr;
import brooklyn.util.net.HasNetworkAddresses;
import brooklyn.util.net.Networking;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;

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
    public void testConfigurePortForwarderType() throws Exception {
        SubnetTier subnetTier2 = app.createAndManageChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDER_TYPE, NoopPortForwarder.class.getName()));

        assertEquals(subnetTier2.getAttribute(SubnetTierImpl.PORT_FORWARDER_LIVE).getClass(), NoopPortForwarder.class);
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
