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

import static org.testng.Assert.assertNotNull;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableMap;

import org.jclouds.cloudstack.domain.PortForwardingRule.Protocol;

import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.entity.core.Entities;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;

public class CloudstackNew40FeaturesClientLiveTest {

    private ManagementContext managementContext;
    private JcloudsLocation loc;
    private CloudstackNew40FeaturesClient client;

    @BeforeMethod(alwaysRun = true)
    public void setUp() throws Exception {
        String locSpec = "jclouds:cloudstack:http://HOSTNAME:8080/client/api/";
        String locIdentity = "IDENTITY";
        String locCredential = "CREDENTIAL";

        managementContext = Entities.newManagementContext();
        loc = (JcloudsLocation) managementContext.getLocationRegistry().resolve(locSpec, ImmutableMap.of("identity", locIdentity, "credential", locCredential));
        client = CloudstackNew40FeaturesClient.newInstance(loc);
    }

    @Test(enabled = false, groups= { "Live", "WIP" })
    public void testFoo() throws Exception {
        String publicIpId = "72aed999-8474-4e41-ad90-3179f6479aed";
        Protocol protocol = Protocol.TCP;
        int publicPort = 11002;
        String targetVmId = "d1654952-bab6-4789-b9ef-b83b10de625a";
        int privatePort = 22;
        String jobId = client.createPortForwardRuleForVm(publicIpId, protocol, publicPort, targetVmId, privatePort);
        assertNotNull(jobId);
    }
}
