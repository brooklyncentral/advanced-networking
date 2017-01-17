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
package brooklyn.networking.cloudstack.legacy;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.apache.brooklyn.api.location.NoMachinesAvailableException;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.BasicConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.config.Sanitizer;
import org.apache.brooklyn.core.location.access.BrooklynAccessUtils;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.location.jclouds.AbstractJcloudsSubnetSshMachineLocation;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsLocationConfig;
import org.apache.brooklyn.location.jclouds.JcloudsMachineLocation;
import org.apache.brooklyn.location.jclouds.JcloudsSshMachineLocation;
import org.apache.brooklyn.location.jclouds.templates.PortableTemplateBuilder;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.internal.ssh.SshTool;
import org.apache.brooklyn.util.core.text.TemplateProcessor;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Networking;
import org.apache.brooklyn.util.repeat.Repeater;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.jclouds.cloudstack.CloudStackApi;
import org.jclouds.cloudstack.compute.options.CloudStackTemplateOptions;
import org.jclouds.cloudstack.domain.AsyncCreateResponse;
import org.jclouds.cloudstack.domain.FirewallRule;
import org.jclouds.cloudstack.domain.NIC;
import org.jclouds.cloudstack.domain.PortForwardingRule;
import org.jclouds.cloudstack.domain.VirtualMachine;
import org.jclouds.cloudstack.features.VirtualMachineApi;
import org.jclouds.cloudstack.options.CreateFirewallRuleOptions;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.OperatingSystem;
import org.jclouds.compute.domain.OsFamily;
import org.jclouds.compute.domain.Template;
import org.jclouds.domain.LoginCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

import brooklyn.networking.NetworkMultiAddressUtils2;
import brooklyn.networking.cloudstack.CloudstackNew40FeaturesClient;

/**
 * Legacy {@link JcloudsLocation} for a CloudStack subnet.
 */
public class LegacyJcloudsCloudstackSubnetLocation extends JcloudsLocation {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyJcloudsCloudstackSubnetLocation.class);

    /* Config on the subnet jclouds location */

    public static final ConfigKey<String> CLOUDSTACK_VPC_ID = ConfigKeys.newStringConfigKey("vpcId");
    public static final ConfigKey<String> CLOUDSTACK_ZONE_ID = ConfigKeys.newStringConfigKey("zoneId");
    public static final ConfigKey<String> CLOUDSTACK_SUBNET_NETWORK_ID = ConfigKeys.newStringConfigKey("networkId");
    public static final ConfigKey<String> CLOUDSTACK_SERVICE_NETWORK_ID = ConfigKeys.newStringConfigKey("serviceNetworkId");

    // For preventing concurrent calls to obtain
    private static final Object mutex = new Object();

    private static long lastObtainTime = -1;

    // Required for port forwarding. Set by location creator (e.g. SubnetTierImpl)
    public static final ConfigKey<PortForwardManager> PORT_FORWARDING_MANAGER = BrooklynAccessUtils.PORT_FORWARDING_MANAGER;

    // Required for port forwarding. Set by subnet creator
    public static final ConfigKey<String> CLOUDSTACK_TIER_PUBLIC_IP_ID = ConfigKeys.newStringConfigKey("publicIpId");

    // Optional, set by creator
    public static final ConfigKey<Cidr> MANAGEMENT_ACCESS_CIDR = new BasicConfigKey<Cidr>(Cidr.class,
            BrooklynAccessUtils.MANAGEMENT_ACCESS_CIDR.getName(),
            BrooklynAccessUtils.MANAGEMENT_ACCESS_CIDR.getDescription(),
            new Cidr("10.0.0.0/8"));

    /* Config on the SSH machine location */

    public static final ConfigKey<String> SUBNET_HOSTNAME_CONFIG = ConfigKeys.newStringConfigKey(LegacySubnetTier.SUBNET_HOSTNAME_SENSOR.getName());
    public static final ConfigKey<String> VM_IDENTIFIER = ConfigKeys.newStringConfigKey("vm.cloud.identifier");

    public LegacyJcloudsCloudstackSubnetLocation(JcloudsLocation parent, ConfigBag map) {
        super(MutableMap.copyOf(parent.getLocalConfigBag().getAllConfig()));
        // TODO
        // TODO NB relying on behaviour implemented in deprecated method
        configure(map.getAllConfig());
    }

    public LegacyJcloudsCloudstackSubnetLocation(Map<?,?> map) {
        super(map);
    }

    public LegacyJcloudsCloudstackSubnetLocation() {
        super();
    }

    protected <T> T getRequiredConfig(ConfigKey<T> key) {
        return checkNotNull(getConfig(key), key.getName());
    }

    @Override
    public JcloudsMachineLocation obtain(Map<?,?> flagsIn) throws NoMachinesAvailableException {
        PortableTemplateBuilder<PortableTemplateBuilder<?>> tb = new PortableTemplateBuilder<PortableTemplateBuilder<?>>();
        tb.locationId(getRequiredConfig(CLOUDSTACK_ZONE_ID));

        // the VM seems to prefer the first network in the list (ie as "default" for gateway)
        // which should be the tier (application) so that app traffic goes via that path
        // (if you put sharedNetwork first, then it can see a brooklyn machine _not_
        // in the sharedNetwork, which is handy for testing)
        List<String> networkIds = new ArrayList<String>();
        networkIds.add(getRequiredConfig(CLOUDSTACK_SUBNET_NETWORK_ID));
        String serviceNetworkId = getConfig(CLOUDSTACK_SERVICE_NETWORK_ID);
        boolean portForwardingMode = Strings.isBlank(serviceNetworkId);
        if (!portForwardingMode)
            networkIds.add(serviceNetworkId);

        tb.options(CloudStackTemplateOptions.Builder
                .setupStaticNat(false)
                .networks(networkIds)
                .dontAuthorizePublicKey()
                .blockUntilRunning(false)
                );

        Map<Object,Object> flags = MutableMap.copyOf(flagsIn)
                .add(TEMPLATE_BUILDER, tb);
        if (portForwardingMode)
                flags.put(WAIT_FOR_SSHABLE, false);

        // gap of 15s does not stop the two NIC's problem
//        log.info("provision - waiting to acquire mutex "+Thread.currentThread());
//        synchronized (mutex) {
//            log.info("provision - acquired mutex "+Thread.currentThread());
//            Time.sleep(15000);
//        }
//        log.info("provision - creating machine "+Thread.currentThread());

        // Throttle to ensure only one call to obtain per 10 seconds (but calls can overlap)
        LOG.info("provision - waiting to acquire mutex ("+Thread.currentThread()+")");
        synchronized (mutex) {
            long now = System.currentTimeMillis();
            if (lastObtainTime >= 0 && (now < (lastObtainTime + 10*1000))) {
                LOG.info("provision - waiting for 10s as another obtain call executed recently "+Thread.currentThread());
                Time.sleep(10000);
            } else {
                LOG.info("provision - contininuing immediately as no other recent call "+Thread.currentThread());
            }

            lastObtainTime = System.currentTimeMillis();
        }

        // And finally obtain the machine
        JcloudsMachineLocation m = (JcloudsMachineLocation) super.obtain(flags);

        // if USE_TWO_NICS -- could check they come up assigned correctly (but probably still too dangerous)
//        String nodeId = m.getNode().getId();
//        CloudstackNew40FeaturesClient client = CloudstackNew40FeaturesClient.newInstance(getEndpoint(), getIdentity(), getCredential());
//        VirtualMachine vm = client.getVirtualMachineClient().getVirtualMachine(nodeId);
//        Set<NIC> nics = vm.getNICs();
//        NIC nic = nics.iterator().next();
//        if (nic.getNetworkId().equals(getRequiredConfig(CLOUDSTACK_TIER_ID))) {
//            String ip = nic.getIPAddress();
//            // ip sometimes does not match subnet
//        }

        return m;
    }

    @Override
    protected JcloudsSshMachineLocation createJcloudsSshMachineLocation(ComputeService computeService, NodeMetadata node, Optional<Template> template,
                                                                        LoginCredentials userCredentials, HostAndPort managementHostAndPort, ConfigBag setup) throws IOException {
        String subnetSpecificHostname = null;
        String vmHostname = managementHostAndPort.getHostText();
        String sshHost = vmHostname;
        Integer sshPort = null;
        PortForwardManager pfw = null;
        String publicIpId = null;

        final String serviceNetworkId = getConfig(CLOUDSTACK_SERVICE_NETWORK_ID);
        boolean portForwardingMode = Strings.isBlank(serviceNetworkId);
        LOG.debug("creating subnet JcloudsSshMachineLocation -- port forwarding={}, node={}", new Object[]{ node, portForwardingMode });
        if (!portForwardingMode) {
            LOG.debug("Using service network for Brooklyn access - service network ID is {} - searching for NIC connected to this network", serviceNetworkId);

            CloudStackApi cloudStackApi = getComputeService().getContext().unwrapApi(CloudStackApi.class);
            VirtualMachineApi vmClient = cloudStackApi.getVirtualMachineApi();
            VirtualMachine vm = vmClient.getVirtualMachine(node.getProviderId());

            Iterable<NIC> allNics = vm.getNICs();
            Predicate<NIC> isServiceNetwork = new Predicate<NIC>() {
                @Override
                public boolean apply(@Nullable NIC input) {
                    return input != null && serviceNetworkId.equals(input.getNetworkId());
                }
            };
            Optional<NIC> serviceNic = Iterables.tryFind(allNics, isServiceNetwork);
            Iterable<NIC> otherNics = Iterables.filter(allNics, Predicates.not(isServiceNetwork));

            checkState(serviceNic.isPresent(), "unable to identify NIC connected to service network " + serviceNetworkId);
            String ipAddress = serviceNic.get().getIPAddress();
            checkState(Strings.isNonBlank(ipAddress), "no IP address on the NIC connected to service network " + serviceNetworkId);

            checkState(!Iterables.isEmpty(otherNics), "VM needs another NIC, in addition to the service network");
            // NIC anotherNic = Iterables.get(otherNics, 0);

            sshHost = ipAddress;
            sshPort = 22;
        } else {
            pfw = getRequiredConfig(PORT_FORWARDING_MANAGER);
            publicIpId = getRequiredConfig(CLOUDSTACK_TIER_PUBLIC_IP_ID);
            Cidr cidr = getConfig(MANAGEMENT_ACCESS_CIDR);

            // others, besides 22!
            int privatePort = 22;
            int publicPort = pfw.acquirePublicPort(publicIpId);

            systemCreatePortForwarding(cidr, publicPort, node, privatePort);

            sshPort = publicPort;
            sshHost = checkNotNull(pfw.getPublicIpHostname(publicIpId), "No ip recorded for id %s", publicIpId);
        }
        LOG.info("Created VM in "+this+" in subnet at "+node+", ssh accessible via "+sshHost+":"+sshPort);

        // and wait for it to be reachable

        LOG.debug("  waiting for new VM "+node+" in "+this+" to be port reachable on "+sshHost+":"+sshPort);
        boolean isReachable = NetworkMultiAddressUtils2.isAccessible(sshHost, sshPort, TimeUnit.MINUTES.toMillis(15));
        if (!isReachable) {
            throw new IllegalStateException("Unable to contact forwarded SSH port for new VM "+node+" in "+this+" on "+sshHost+":"+sshPort);
        }

        if (!NetworkMultiAddressUtils2.isResolveable(vmHostname)) {
            String oldHostname = vmHostname;
            vmHostname = Iterables.getFirst(
                            Iterables.concat(node.getPublicAddresses(), node.getPrivateAddresses()), null);
            LOG.info("Renaming unresolvable hostname "+oldHostname+" to "+vmHostname);
        }

        // "public hostname" might be different
        // - sometimes it is not pingable from brooklyn (making sensors hard)
        // - sometimes furthest is the public one, we might want it
        //   (eg if we are in different 10.x subnet - closest not always accessible)
        //   or we might want nearest (if public is not accessible);
        //   and also consider we are on /16 subnet with host, host has another 10.x/8 address, but no public address;
        //   ie furthest might be inaccessible for other reasons
        // TODO i think the "right" way to do this is to have a pluggable "address chooser" ?

        LOG.debug("  vmHostname: "+vmHostname);

        // supply forwarded host and port
        Map<String,Object> sshConfig = extractSshConfig(setup, node);
        sshConfig.put(SshMachineLocation.SSH_HOST.getName(), sshHost);
        if (sshPort!=null)
            sshConfig.put(SshMachineLocation.SSH_PORT.getName(), sshPort);

        if (LOG.isDebugEnabled()) {
            LOG.debug("creating JcloudsSshMachineLocation in subnet {}, service network {}, for {}@{} for {} with {}",
                    new Object[] {
                    getRequiredConfig(CLOUDSTACK_SUBNET_NETWORK_ID),
                    getConfig(CLOUDSTACK_SERVICE_NETWORK_ID),
                    userCredentials.getUser(),
                    vmHostname,
                    setup.getDescription(),
                    Sanitizer.sanitize(sshConfig)
            });
        }

        final JcloudsSshMachineLocation l = new AbstractJcloudsSubnetSshMachineLocation(
                MutableMap.builder()
                        .put("address", Networking.getInetAddressWithFixedName(vmHostname))
                        .put("displayName", vmHostname)
                        .put("user", userCredentials.getUser())
                        // don't think "config" does anything
                        .putAll(sshConfig)
                        .put("config", sshConfig)
                        .put("jcloudsParent", this)
                        .put(SshMachineLocation.PASSWORD, userCredentials.getOptionalPassword().orNull())
                        .put(SshMachineLocation.PRIVATE_KEY_DATA, userCredentials.getOptionalPrivateKey().orNull())
                        .put("node", node)
                        .put("template", template.orNull())
                        .put("port", sshPort)
                        .put(CALLER_CONTEXT, setup.get(CALLER_CONTEXT))
                        .build(), this, node) {
            @Override
            public HostAndPort getSocketEndpointFor(Cidr accessor, int privatePort) {
                return getPortForwardingTo(accessor, this, privatePort);
            }
        };
        l.init();
        getManagementContext().getLocationManager().manage(l);

        l.config().set(SUBNET_HOSTNAME_CONFIG, subnetSpecificHostname);
        l.config().set(VM_IDENTIFIER, node.getId());

        if (portForwardingMode) {
            // record port 22 forwarding
            pfw.associate(publicIpId, sshPort, l, 22);
        }


        LOG.debug("  waiting for new VM {} in {} to be SSH reachable on {}:{}", new Object[]{node, this, sshHost, sshPort});
        final AtomicBoolean isActive = new AtomicBoolean(false);
        Repeater.create()
                .repeat(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            int rc = l.execCommands("test accessibility", ImmutableList.of("hostname"));
                            isActive.set(rc == 0);
                        } catch(Throwable t) {
                            isActive.set(false);
                        }
                    }
                })
                .every(Duration.FIVE_SECONDS)
                .until(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        return isActive.get();
                    }
                })
                .limitTimeTo(Duration.FIVE_MINUTES)
                .run();
        LOG.debug("  waited  for new VM {} in {} to be SSH reachable on {}:{}, result={}", new Object[]{node, this, sshHost, sshPort, isActive.get()});

        OperatingSystem operatingSystem = l.getNode().getOperatingSystem();
        if (operatingSystem != null) {
            OsFamily family = operatingSystem.getFamily();
            LOG.info("VM {}: OS family is {}", new Object[]{node, family});
            if (family != OsFamily.WINDOWS && family != OsFamily.UNRECOGNIZED) {
                LOG.warn("VM {}: disabling iptables", new Object[]{node});
                l.execScript(MutableMap.of(SshTool.PROP_ALLOCATE_PTY.getName(), true), "disabling requiretty",
                        Arrays.asList(BashCommands.dontRequireTtyForSudo()));
                l.execScript("disabling iptables", Arrays.asList(
                        "sudo /etc/init.d/iptables stop",
                        "sudo chkconfig iptables off"));
            } else {
                LOG.warn("VM {}: NOT disabling iptables because OS family is {}", new Object[]{node, family});
            }
        } else {
            LOG.warn("VM {}: NOT disabling iptables because OS is not detected", new Object[]{node});
        }

        String setupScript = setup.get(JcloudsLocationConfig.CUSTOM_MACHINE_SETUP_SCRIPT_URL);
        if (Strings.isNonBlank(setupScript)) {
            String setupVarsString = setup.get(JcloudsLocationConfig.CUSTOM_MACHINE_SETUP_SCRIPT_VARS);
            Map<String, String> substitutions = (setupVarsString != null)
                    ? Splitter.on(",").withKeyValueSeparator(":").split(setupVarsString)
                    : ImmutableMap.<String, String>of();
            String scriptContent =  ResourceUtils.create(this).getResourceAsString(setupScript);
            String script = TemplateProcessor.processTemplateContents(scriptContent, substitutions);
            l.execScript(MutableMap.of(SshTool.PROP_ALLOCATE_PTY.getName(), true), "disabling requiretty",
                    Arrays.asList(BashCommands.dontRequireTtyForSudo()));
            l.execCommands("Customizing node " + this, ImmutableList.of(script));
        }

        return l;
    }

    protected HostAndPort getManagementPortForwardingTo(JcloudsSshMachineLocation l, int privatePort) {
        return getPortForwardingTo(getConfig(MANAGEMENT_ACCESS_CIDR), l, privatePort);
    }

    protected HostAndPort getPortForwardingTo(Cidr access, JcloudsSshMachineLocation l, int privatePort) {
        PortForwardManager pfw = getRequiredConfig(PORT_FORWARDING_MANAGER);
        synchronized (pfw) {
            HostAndPort hp = pfw.lookup(l, privatePort);
            if (hp!=null) return hp;
            //
            String publicIpId = getConfig(CLOUDSTACK_TIER_PUBLIC_IP_ID);
            int publicPort = pfw.acquirePublicPort(publicIpId, l, privatePort);
            systemCreatePortForwarding(access, publicPort, l.getNode(), privatePort);
            return pfw.lookup(l, privatePort);
        }
    }

    protected void systemCreatePortForwarding(Cidr cidr, int publicPort, NodeMetadata node, int privatePort) {
        // TODO which needs enabling -- private or public port?
        // should we do this at subnet, for the entire range ?
        try {
            String publicIpId = getRequiredConfig(CLOUDSTACK_TIER_PUBLIC_IP_ID);
            CloudstackNew40FeaturesClient client = CloudstackNew40FeaturesClient.newInstance(getEndpoint(), getIdentity(), getCredential());
            String jobid;
            if (!Strings.isBlank(getConfig(CLOUDSTACK_VPC_ID))) {
                String networkId = getRequiredConfig(CLOUDSTACK_SUBNET_NETWORK_ID);
                jobid = client.createPortForwardRule(networkId, publicIpId, PortForwardingRule.Protocol.TCP, publicPort, node.getId(), privatePort);
                client.waitForJobSuccess(jobid);
                client.createVpcNetworkAcl(networkId, "TCP", cidr.toString(), publicPort, publicPort, null, null, "INGRESS");
                // private doesn't need to be opened
//                client.createVpcNetworkAcl(tierId, "TCP", cidr.toString(), privatePort, privatePort, null, null, "INGRESS");
            } else {
                jobid = client.createPortForwardRuleForVm(publicIpId, PortForwardingRule.Protocol.TCP, publicPort, node.getId(), privatePort);
                client.waitForJobSuccess(jobid);

                CreateFirewallRuleOptions options;
                options = CreateFirewallRuleOptions.Builder.
                        startPort(publicPort).endPort(publicPort).CIDRs(ImmutableSet.of(cidr.toString()));
                AsyncCreateResponse job = client.getCloudstackGlobalClient().getFirewallApi().createFirewallRuleForIpAndProtocol(
                        publicIpId, FirewallRule.Protocol.TCP, options);
                client.waitForJobSuccess(job.getJobId());
                // private doesn't need to be opened
//                options = CreateFirewallRuleOptions.Builder.
//                        startPort(privatePort).endPort(privatePort).CIDRs(ImmutableSet.of(cidr.toString()));
//                job = client.getCloudstackGlobalClient().getFirewallClient().createFirewallRuleForIpAndProtocol(
//                        publicIpId, FirewallRule.Protocol.TCP, options);
//                client.waitForJob(job.getJobId());
            }
        } catch (Exception e) {
            LOG.warn("Could not create fwd/ACL (possibly already created) to "+this+" port "+privatePort+": "+e);
        }
    }
}
