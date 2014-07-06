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
package brooklyn.networking.portforwarding;

import java.io.File;
import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import brooklyn.entity.Application;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.rebind.RebindTestUtils;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.access.PortMapping;
import brooklyn.management.ManagementContext;
import brooklyn.management.internal.LocalManagementContext;
import brooklyn.networking.subnet.SubnetTier;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.net.Cidr;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

public class PortForwarderSubnetTierRebindUnitTest extends AbstractPortForwarderIptablesTest {

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(PortForwarderSubnetTierRebindUnitTest.class);

    private SubnetTier subnetTier;

    private File mementoDir;

    @Override
    protected boolean requiresMachines() {
        return false;
    }

    @Override
    protected ManagementContext newManagementContext() {
        if (managementContext!=null) throw new IllegalStateException("must reset mgmt context before invoking");
        mementoDir = Files.createTempDir();
        return RebindTestUtils.newPersistingManagementContext(mementoDir, getClass().getClassLoader(), 1);
    }

    @Test
    public void testRebindSubnetTier() throws Exception {
        subnetTier = app.createAndManageChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDING_MANAGER, portForwardManager)
                .configure(SubnetTier.PORT_FORWARDER, portForwarder)
                .configure(SubnetTier.SUBNET_CIDR, Cidr.UNIVERSAL));

        TestEntity testChild = subnetTier.addChild(EntitySpec.create(TestEntity.class));
        Entities.startManagement(testChild);

        // don't start it, so the port-forwarder isn't actually used
        // (this is a brittle hack but it should work)
//        app.start(ImmutableList.<Location>of());

        int p1 = testChild.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).acquirePublicPort("p1");
        PortForwardManager pfm1 = Preconditions.checkNotNull( testChild.getConfig(SubnetTier.PORT_FORWARDING_MANAGER) );
        Collection<PortMapping> mappings1 = pfm1.getPortMappingWithPublicIpId("p1");
        Assert.assertEquals(mappings1.size(), 1);
        Assert.assertEquals(mappings1.iterator().next().getPublicPort(), p1);

        Application newApp = rebind();
        Entity subnetRebinded = Iterables.getOnlyElement(newApp.getChildren());
        PortForwardManager pfm2 = Preconditions.checkNotNull( subnetRebinded.getConfig(SubnetTier.PORT_FORWARDING_MANAGER) );
        Collection<PortMapping> mappings1a = pfm2.getPortMappingWithPublicIpId("p1");
        Assert.assertEquals(mappings1, mappings1a);

        Entity testChildRebinded = Iterables.getOnlyElement(subnetRebinded.getChildren());
        Collection<PortMapping> mappings1b = testChildRebinded.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).getPortMappingWithPublicIpId("p1");
        Assert.assertEquals(mappings1, mappings1b);

        int p2 = subnetRebinded.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).acquirePublicPort("p2");
        Collection<PortMapping> mappings2a = subnetRebinded.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).getPortMappingWithPublicIpId("p2");
        Collection<PortMapping> mappings2b = testChildRebinded.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).getPortMappingWithPublicIpId("p2");
        Assert.assertEquals(mappings2a, mappings2b);
        Assert.assertEquals(mappings2a.iterator().next().getPublicPort(), p2);

        int p3 = testChildRebinded.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).acquirePublicPort("p3");
        Collection<PortMapping> mappings3a = subnetRebinded.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).getPortMappingWithPublicIpId("p3");
        Collection<PortMapping> mappings3b = testChildRebinded.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).getPortMappingWithPublicIpId("p3");
        Assert.assertEquals(mappings3a, mappings3b);
        Assert.assertEquals(mappings3a.iterator().next().getPublicPort(), p3);
    }

    protected Application rebind() throws Exception {
        RebindTestUtils.waitForPersisted(app);
        ((LocalManagementContext)managementContext).terminate();
        managementContext = null;
        Application result = RebindTestUtils.rebind(mementoDir, getClass().getClassLoader());
        managementContext = result.getManagementContext();
        return result;
    }



}
