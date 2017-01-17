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
package brooklyn.networking.cloudstack.loadbalancer;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.net.URL;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.jclouds.cloudstack.domain.AsyncCreateResponse;
import org.jclouds.cloudstack.domain.FirewallRule;
import org.jclouds.cloudstack.domain.Network;
import org.jclouds.cloudstack.domain.PublicIPAddress;
import org.jclouds.cloudstack.options.AssociateIPAddressOptions;
import org.jclouds.cloudstack.options.CreateFirewallRuleOptions;
import org.jclouds.cloudstack.options.CreateNetworkOptions;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.location.LocationConfigKeys;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.core.location.access.PortForwardManagerAuthority;
import org.apache.brooklyn.core.location.cloud.names.BasicCloudMachineNamer;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.entity.group.DynamicCluster;
import org.apache.brooklyn.entity.webapp.jboss.JBoss7Server;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsSshMachineLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.http.HttpAsserts;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.text.Identifiers;

import brooklyn.networking.cloudstack.CloudstackNew40FeaturesClient;
import brooklyn.networking.cloudstack.legacy.LegacyJcloudsCloudstackSubnetLocation;

// FIXME: Tests currently failing with NPE
@Test(groups={"WIP"})
public class CloudStackLoadBalancerLiveTest extends BrooklynAppLiveTestSupport {

    private static final Logger LOG = LoggerFactory.getLogger(CloudStackLoadBalancerLiveTest.class);

    public static final String PROVIDER = "cloudstack";
    public static final String ENDPOINT_OVERRIDE = System.getProperty("brooklyn.cloudstack.endpointOverride");
    public static final String LOCATION_SPEC_DEFAULT = PROVIDER + (ENDPOINT_OVERRIDE == null ? "" : ":" + ENDPOINT_OVERRIDE);
    public static final String LOCATION_SPEC_OVERRIDE = System.getProperty("brooklyn.cloudstack.locationSpecOverride");

    private URL warUrl;

    // Image: {id=us-east-1/ami-7d7bfc14, providerId=ami-7d7bfc14, name=RightImage_CentOS_6.3_x64_v5.8.8.5, location={scope=REGION, id=us-east-1, description=us-east-1, parent=aws-ec2, iso3166Codes=[US-VA]}, os={family=centos, arch=paravirtual, version=6.0, description=rightscale-us-east/RightImage_CentOS_6.3_x64_v5.8.8.5.manifest.xml, is64Bit=true}, description=rightscale-us-east/RightImage_CentOS_6.3_x64_v5.8.8.5.manifest.xml, version=5.8.8.5, status=AVAILABLE[available], loginUser=root, userMetadata={owner=411009282317, rootDeviceType=instance-store, virtualizationType=paravirtual, hypervisor=xen}}
    //runTest(ImmutableMap.of("imageId", "us-east-1/ami-7d7bfc14", "hardwareId", SMALL_HARDWARE_ID));

    protected BrooklynProperties brooklynProperties;

    protected JcloudsLocation loc;
    protected List<Location> locs;
    private CloudstackNew40FeaturesClient client;
    private PortForwardManager portForwardManager;
    private String zoneId;
    private String networkId;
    private String publicIpId;

    private CloudStackLoadBalancer lb;

    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        warUrl = checkNotNull(getClass().getClassLoader().getResource("hello-world.war"));
        
        brooklynProperties = BrooklynProperties.Factory.newDefault();

        String locationSpec;
        if (LOCATION_SPEC_OVERRIDE != null) {
            locationSpec = LOCATION_SPEC_OVERRIDE;
        } else {
            // Don't let any defaults from brooklyn.properties (except credentials) interfere with test
            brooklynProperties = BrooklynProperties.Factory.newDefault();
            brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-description-regex");
            brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-name-regex");
            brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".image-id");
            brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".inboundPorts");
            brooklynProperties.remove("brooklyn.jclouds."+PROVIDER+".hardware-id");

            // Also removes scriptHeader (e.g. if doing `. ~/.bashrc` and `. ~/.profile`, then that can cause "stdin: is not a tty")
            brooklynProperties.remove("brooklyn.ssh.config.scriptHeader");

            locationSpec = LOCATION_SPEC_DEFAULT;
        }

        mgmt = new LocalManagementContext(brooklynProperties);

        super.setUp();

        Map<String,?> flags = ImmutableMap.of("tags", ImmutableList.of(getClass().getName()));
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(locationSpec, flags);
        locs = ImmutableList.<Location>of(loc);
        portForwardManager = new PortForwardManagerAuthority();

        client = CloudstackNew40FeaturesClient.newInstance(loc);
        zoneId = loc.getRegion();
    }

    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        try {
            if (lb != null) {
                lb.deleteLoadBalancer();
            }
            if (app != null) {
                app.stop();
            }

            if (networkId != null) {
                String job = client.getNetworkClient().deleteNetwork(networkId);
                client.waitForJobsSuccess(ImmutableList.of(job));
            }
        } catch (Exception e) {
            LOG.error("error deleting/stopping ELB app; continuing with shutdown...", e);
        } finally {
            super.tearDown();
        }
    }

    // TODO Copied from LegacySubnetTierImpl; use generic code when available
    protected String createNetwork(String zoneId) {
        LOG.info("Creating network in zone {}", zoneId);
        String nwOffering = client.getNetworkOfferingWithName("DefaultIsolatedNetworkOfferingWithSourceNatService");
        // FIXME hardcoded identifier above
        CreateNetworkOptions options = new CreateNetworkOptions();
        String name = new BasicCloudMachineNamer().generateNewGroupId(new ConfigBag().configure(LocationConfigKeys.CALLER_CONTEXT, this));
        Network nw = client.getNetworkClient().createNetworkInZone(zoneId, nwOffering, name, name+" subnet", options);
        return nw.getId();
    }

    protected String createPublicIp(String zoneId, String networkId) {
        LOG.info("Creating public IP in zone {}, network {}", zoneId, networkId);
        AsyncCreateResponse response = client.getCloudstackGlobalClient().getAddressApi().associateIPAddressInZone(zoneId,
                AssociateIPAddressOptions.Builder.networkId(networkId));
        client.waitForJobSuccess(response.getJobId());
        String ipId = response.getId();
        PublicIPAddress ip = client.getCloudstackGlobalClient().getAddressApi().getPublicIPAddress(ipId);

        portForwardManager.recordPublicIpHostname(ip.getId(), ip.getIPAddress());

        return ip.getId();
    }

    private void openPublicPort(String publicIpId, int port, Cidr cidr) {
        LOG.info("Opening public IP:port {}:{} in zone {}, network {}", new Object[] {publicIpId, port});
        CreateFirewallRuleOptions options = CreateFirewallRuleOptions.Builder.
                startPort(port).endPort(port).CIDRs(ImmutableSet.of(cidr.toString()));
        AsyncCreateResponse job = client.getCloudstackGlobalClient().getFirewallApi().createFirewallRuleForIpAndProtocol(
                publicIpId, FirewallRule.Protocol.TCP, options);
        client.waitForJobsSuccess(ImmutableList.of(job.getJobId()));
    }

    @Test(groups="Live")
    public void testCreateLoadBalancer() throws Exception {
        networkId = createNetwork(zoneId);
        publicIpId = createPublicIp(zoneId, networkId);
        openPublicPort(publicIpId, 1234, Cidr.UNIVERSAL);

        lb = app.createAndManageChild(EntitySpec.create(CloudStackLoadBalancer.class)
                .configure(CloudStackLoadBalancer.LOAD_BALANCER_NAME, "myname-"+System.getProperty("user.name")+"-"+Identifiers.makeRandomId(8))
                .configure(CloudStackLoadBalancer.PUBLIC_IP_ID, publicIpId)
                .configure(CloudStackLoadBalancer.PROXY_HTTP_PORT, PortRanges.fromInteger(1234))
                .configure(CloudStackLoadBalancer.INSTANCE_PORT, 8080));

        app.start(locs);

        assertNotNull(lb.getAttribute(CloudStackLoadBalancer.HOSTNAME));
        assertNotNull(lb.getAttribute(CloudStackLoadBalancer.LOAD_BALANCER_ID));
        assertEquals(lb.getAttribute(CloudStackLoadBalancer.ROOT_URL), "http://"+lb.getAttribute(CloudStackLoadBalancer.HOSTNAME)+":1234/");
        assertTrue(lb.getAttribute(CloudStackLoadBalancer.SERVICE_UP));
        assertEquals(lb.getAttribute(CloudStackLoadBalancer.SERVICE_STATE), Lifecycle.RUNNING);

        lb.reload();
    }

    @Test(groups="Live")
    public void testLoadBalancerWithHttpTargets() throws Exception {
        networkId = createNetwork(zoneId);
        publicIpId = createPublicIp(zoneId, networkId);
        openPublicPort(publicIpId, 1234, Cidr.UNIVERSAL);

        LegacyJcloudsCloudstackSubnetLocation subnetLoc = new LegacyJcloudsCloudstackSubnetLocation(loc, ConfigBag.newInstance()
                .configure(LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_SUBNET_NETWORK_ID, networkId)
                .configure(LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_ZONE_ID, zoneId)
                .configure(LegacyJcloudsCloudstackSubnetLocation.MANAGEMENT_ACCESS_CIDR, Cidr.UNIVERSAL)
                .configure(LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_TIER_PUBLIC_IP_ID, publicIpId)
                .configure(LegacyJcloudsCloudstackSubnetLocation.PORT_FORWARDING_MANAGER, portForwardManager));

        DynamicCluster cluster = app.createAndManageChild(EntitySpec.create(DynamicCluster.class)
                .configure(DynamicCluster.INITIAL_SIZE, 1)
                .configure(DynamicCluster.MEMBER_SPEC, EntitySpec.create(JBoss7Server.class)
                        .configure("war", warUrl.toString())
                        .configure(JBoss7Server.HTTP_PORT, PortRanges.fromInteger(8080))));

        lb = app.createAndManageChild(EntitySpec.create(CloudStackLoadBalancer.class)
                .configure(CloudStackLoadBalancer.SERVER_POOL, cluster)
                .configure(CloudStackLoadBalancer.LOAD_BALANCER_NAME, "myname-"+System.getProperty("user.name")+"-"+Identifiers.makeRandomId(8))
                .configure(CloudStackLoadBalancer.PUBLIC_IP_ID, publicIpId)
                .configure(CloudStackLoadBalancer.PROXY_HTTP_PORT, PortRanges.fromInteger(1234))
                .configure(CloudStackLoadBalancer.INSTANCE_PORT, 8080));

        app.start(ImmutableList.of(subnetLoc));

        JBoss7Server appserver = (JBoss7Server) Iterables.getOnlyElement(cluster.getMembers());
        JcloudsSshMachineLocation machine = (JcloudsSshMachineLocation) Iterables.getOnlyElement(appserver.getLocations());

        // double-check that jboss really is reachable (so don't complain about ELB if it's not ELB's fault!)
        String directurl = appserver.getAttribute(JBoss7Server.ROOT_URL);
        HttpAsserts.assertHttpStatusCodeEventuallyEquals(directurl, 200);
        HttpAsserts.assertContentContainsText(directurl, "Hello");

        Asserts.succeedsEventually(ImmutableMap.of("timeout", 5*60*1000), new Runnable() {
                @Override public void run() {
                    String url = "http://"+lb.getAttribute(CloudStackLoadBalancer.HOSTNAME)+":80/";
                    HttpAsserts.assertHttpStatusCodeEventuallyEquals(url, 200);
                    HttpAsserts.assertContentContainsText(url, "Hello");
                }});

        assertEquals(lb.getAttribute(CloudStackLoadBalancer.SERVER_POOL_TARGETS), ImmutableSet.of(machine.getNode().getProviderId()));
    }
}
