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
package brooklyn.networking.portforwarding;

import java.io.File;
import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.proxying.EntitySpec;
import org.apache.brooklyn.api.management.ManagementContext;
import org.apache.brooklyn.core.management.internal.LocalManagementContext;
import org.apache.brooklyn.location.access.PortForwardManager;
import org.apache.brooklyn.location.access.PortMapping;
import org.apache.brooklyn.test.entity.TestEntity;
import org.apache.brooklyn.util.net.Cidr;

import brooklyn.entity.basic.Entities;
import brooklyn.entity.rebind.RebindTestUtils;
import brooklyn.networking.subnet.SubnetTier;

public class PortForwarderSubnetTierRebindUnitTest extends AbstractPortForwarderIptablesTest {

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(PortForwarderSubnetTierRebindUnitTest.class);

    private SubnetTier origSubnetTier;

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
        origSubnetTier = app.createAndManageChild(EntitySpec.create(SubnetTier.class)
                .configure(SubnetTier.PORT_FORWARDING_MANAGER, portForwardManager)
                .configure(SubnetTier.PORT_FORWARDER_TYPE, portForwarderType)
                .configure(PortForwarderIptables.FORWARDER_IP, forwarderPublicIp)
                .configure(PortForwarderIptables.FORWARDER_MACHINE, forwarderMachine)
                .configure(SubnetTier.SUBNET_CIDR, Cidr.UNIVERSAL));

        TestEntity origTestChild = origSubnetTier.addChild(EntitySpec.create(TestEntity.class));
        Entities.startManagement(origTestChild);

        // don't start it, so the port-forwarder isn't actually used
        // (this is a brittle hack but it should work)
//        app.start(ImmutableList.<Location>of());

        // Create a mapping
        int p1 = origTestChild.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).acquirePublicPort("p1");
        PortForwardManager origPfm = Preconditions.checkNotNull( origTestChild.getConfig(SubnetTier.PORT_FORWARDING_MANAGER) );
        Collection<PortMapping> mappings1 = origPfm.getPortMappingWithPublicIpId("p1");
        Assert.assertEquals(mappings1.size(), 1);
        Assert.assertEquals(mappings1.iterator().next().getPublicPort(), p1);

        // Rebind, and check mapping still exists
        Application newApp = rebind();
        Entity newSubnetTier = Iterables.getOnlyElement(newApp.getChildren());
        PortForwardManager newPfm = Preconditions.checkNotNull( newSubnetTier.getConfig(SubnetTier.PORT_FORWARDING_MANAGER) );
        Collection<PortMapping> mappings1a = newPfm.getPortMappingWithPublicIpId("p1");
        Assert.assertEquals(mappings1, mappings1a);

        // Check that PFM of child entity still gives same result
        Entity newTestChild = Iterables.getOnlyElement(newSubnetTier.getChildren());
        Collection<PortMapping> mappings1b = newTestChild.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).getPortMappingWithPublicIpId("p1");
        Assert.assertEquals(mappings1, mappings1b);

        // Check that PFM is still usable (and same instance being shared by parent and child entity)
        int p2 = newSubnetTier.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).acquirePublicPort("p2");
        Collection<PortMapping> mappings2a = newSubnetTier.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).getPortMappingWithPublicIpId("p2");
        Collection<PortMapping> mappings2b = newTestChild.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).getPortMappingWithPublicIpId("p2");
        Assert.assertEquals(mappings2a, mappings2b);
        Assert.assertEquals(mappings2a.iterator().next().getPublicPort(), p2);

        int p3 = newTestChild.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).acquirePublicPort("p3");
        Collection<PortMapping> mappings3a = newSubnetTier.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).getPortMappingWithPublicIpId("p3");
        Collection<PortMapping> mappings3b = newTestChild.getConfig(SubnetTier.PORT_FORWARDING_MANAGER).getPortMappingWithPublicIpId("p3");
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
