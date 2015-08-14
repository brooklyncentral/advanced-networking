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
package brooklyn.networking.cloudstack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.apache.brooklyn.api.location.NoMachinesAvailableException;
import org.apache.brooklyn.api.management.ManagementContext;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsMachineLocation;

import brooklyn.config.BrooklynProperties;
import brooklyn.entity.basic.Entities;
import brooklyn.management.internal.LocalManagementContext;

/**
 * FIXME Test assumes that:
 *  - brooklyn.location.named.citrix-cloudplatform is defined in brooklyn.properties
 *  - brooklyn.location.named.citrix-cloudplatform.accountName is set with the correct account name
 *
 * TODO Also need to:
 *  - test in both an account that already has a private network, and in an account that does not
 *    (and assert that a new network is not created for the former).
 */
public class CloudStackPrivateNetworkLocationCustomizerLiveTest {

    private static final Logger LOG = LoggerFactory.getLogger(CloudStackPrivateNetworkLocationCustomizerLiveTest.class);

    private BrooklynProperties brooklynProperties;
    private ManagementContext managementContext;
    private JcloudsLocation loc;
    private JcloudsMachineLocation machine;

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        brooklynProperties = BrooklynProperties.Factory.newDefault();
        managementContext = new LocalManagementContext(brooklynProperties);
        loc = (JcloudsLocation) managementContext.getLocationRegistry().resolve("citrix-cloudplatform");
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        try {
            if (machine != null) loc.release(machine);
        } finally {
            if (managementContext != null) Entities.destroyAll(managementContext);
        }
    }

    @Test(groups="Live")
    public void testCreateMachineInPrivateSubnet() throws NoMachinesAvailableException {
        LOG.info("Images=" + Joiner.on("\n\t").join(loc.getComputeService().listImages()));
        LOG.info("HardwareProfiles=" + Joiner.on("\n\t").join(loc.getComputeService().listHardwareProfiles()));
        LOG.info("AssignableLocations=" + Joiner.on("\n\t").join(loc.getComputeService().listAssignableLocations()));
        LOG.info("Nodes=" + Joiner.on("\n\t").join(loc.getComputeService().listNodes()));

        machine = (JcloudsMachineLocation) loc.obtain(ImmutableMap.of(
                "openPorts", ImmutableList.of(22),
                "customizer", new CloudStackPrivateNetworkLocationCustomizer()));
        LOG.info("Created machine {}", machine);
    }
}
