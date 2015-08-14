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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;

import org.jclouds.cloudstack.domain.AsyncJob;
import org.jclouds.cloudstack.domain.LoadBalancerRule;
import org.jclouds.cloudstack.domain.LoadBalancerRule.Algorithm;
import org.jclouds.cloudstack.domain.PublicIPAddress;
import org.jclouds.cloudstack.domain.VirtualMachine;
import org.jclouds.cloudstack.features.LoadBalancerApi;
import org.jclouds.cloudstack.options.CreateLoadBalancerRuleOptions;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.entity.proxy.AbstractNonProvisionedControllerImpl;
import org.apache.brooklyn.entity.proxy.LoadBalancer;
import org.apache.brooklyn.location.access.BrooklynAccessUtils;
import org.apache.brooklyn.location.cloud.names.BasicCloudMachineNamer;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsSshMachineLocation;

import brooklyn.config.ConfigKey;
import brooklyn.config.ConfigKey.HasConfigKey;
import brooklyn.entity.basic.Lifecycle;
import brooklyn.event.feed.ConfigToAttributes;
import brooklyn.networking.cloudstack.CloudstackNew40FeaturesClient;
import brooklyn.util.config.ConfigBag;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.text.Strings;

public class CloudStackLoadBalancerImpl extends AbstractNonProvisionedControllerImpl implements CloudStackLoadBalancer {

    private static final Logger LOG = LoggerFactory.getLogger(CloudStackLoadBalancerImpl.class);

    private JcloudsLocation loc;
    private CloudstackNew40FeaturesClient client;
    private LoadBalancerApi loadBalancerApi;

    protected String inferProtocol() {
        // TODO support other protocols?!
        return "http";
    }

    protected String getProtocol() {
        return getAttribute(PROTOCOL);
    }

    protected Integer getPort() {
        return getAttribute(PROXY_HTTP_PORT);
    }

    /** returns URL, if it can be inferred; null otherwise */
    protected String inferUrl(boolean requireManagementAccessible) {
        String protocol = checkNotNull(getProtocol(), "no protocol configured");
        Integer port = checkNotNull(getPort(), "no port configured (the requested port may be in use)");
        String hostname = getAttribute(LoadBalancer.HOSTNAME);
        if (requireManagementAccessible) {
            HostAndPort accessible = BrooklynAccessUtils.getBrooklynAccessibleAddress(this, port);
            if (accessible!=null) {
                hostname = accessible.getHostText();
                port = accessible.getPort();
            }
        }
        if (hostname==null) return null;
        return protocol+"://"+hostname+":"+port+"/";
    }

    protected String inferUrl() {
        return inferUrl(false);
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        ConfigToAttributes.apply(this);

        addLocations(locations);
        setAttribute(SERVICE_UP, false);
        setAttribute(SERVICE_STATE, Lifecycle.STARTING);
        try {
            super.start(locations);

            checkArgument(locations.size() == 1, "start must have exactly one location, but given %s (%s)", locations.size(), locations);
            Location onlyloc = Iterables.getOnlyElement(locations);
            checkArgument(onlyloc instanceof JcloudsLocation, "start must have exactly one location, of type JcloudsLocation, but given %s (%s)", (onlyloc != null ? onlyloc.getClass() : null), onlyloc);
            loc = (JcloudsLocation) onlyloc;
            checkArgument("cloudstack".equals(loc.getProvider()), "start must have exactly one jclouds location for cloudstack, but given provider %s (%s)", loc.getProvider(), loc);

            client = CloudstackNew40FeaturesClient.newInstance(loc);
            loadBalancerApi = client.getLoadBalancerClient();

            startLoadBalancer();
            setAttribute(SERVICE_UP, true);
            setAttribute(SERVICE_STATE, Lifecycle.RUNNING);
            isActive = true;
        } catch (Exception e) {
            setAttribute(SERVICE_STATE, Lifecycle.ON_FIRE);
            throw Exceptions.propagate(e);
        }
    }

    @Override
    public void stop() {
        // TODO Should we delete the load balancer?
        loc = null;
        setAttribute(SERVICE_STATE, Lifecycle.STOPPING);
        setAttribute(SERVICE_UP, false);
        setAttribute(SERVICE_STATE, Lifecycle.STOPPED);
    }

    @Override
    public void restart() {
        // no-op
    }

    @Override
    protected void reconfigureService() {
        // see #reload(); no prep required in reconfigureService
    }

    @Override
    public void reload() {
        String publicIPId = getRequiredConfig(PUBLIC_IP_ID);
        String lbId = getAttribute(LOAD_BALANCER_ID);
        if (lbId == null) {
            LOG.warn("Not updating load balancer {} ({} in {}), ipId {}, because id not set", new Object[] {this, lbId, loc, publicIPId});
        }

        LOG.debug("Updating load balancer {} ({} in {}), ipId {}", new Object[] {this, lbId, loc, publicIPId});

        Set<VirtualMachine> oldVirtualMachines = loadBalancerApi.listVirtualMachinesAssignedToLoadBalancerRule(lbId);
        Set<String> oldVirtualMachineIds = Sets.newLinkedHashSet();
        for (VirtualMachine virtualMachine : oldVirtualMachines) {
            oldVirtualMachineIds.add(virtualMachine.getId());
        }

        Set<String> currentVirtualMachineIds = Sets.newLinkedHashSet();
        for (String address : serverPoolAddresses) {
            currentVirtualMachineIds.add(address);
        }

        Set<String> removedVirtualMachineIds = Sets.difference(oldVirtualMachineIds, currentVirtualMachineIds);
        Set<String> addedVirtualMachineIds = Sets.difference(currentVirtualMachineIds, oldVirtualMachineIds);

        LOG.debug("Updating load balancer {} ({} in {}), ipId {}: adding {}, removing {}", new Object[] {this, lbId, loc, publicIPId, addedVirtualMachineIds, removedVirtualMachineIds});

        if (addedVirtualMachineIds.size() > 0) {
            loadBalancerApi.assignVirtualMachinesToLoadBalancerRule(lbId, addedVirtualMachineIds);
        }
        if (removedVirtualMachineIds.size() > 0) {
            loadBalancerApi.removeVirtualMachinesFromLoadBalancerRule(lbId, removedVirtualMachineIds);
        }
    }

    @Override
    protected String getAddressOfEntity(Entity member) {
        JcloudsSshMachineLocation machine = (JcloudsSshMachineLocation) Iterables.find(member.getLocations(),
                Predicates.instanceOf(JcloudsSshMachineLocation.class), null);

        if (machine != null && machine.getNode().getProviderId() != null) {
            return machine.getNode().getProviderId();
        } else {
            LOG.error("Unable to construct cloudstack-id representation for {} in {}; skipping in {}", new Object[] { member, machine, this });
            return null;
        }
    }

    protected void startLoadBalancer() {
        String lbName = getAttribute(LOAD_BALANCER_NAME);
        if (Strings.isBlank(lbName)) {
            ConfigBag setup = ConfigBag.newInstance(getAllConfig());
            lbName = new BasicCloudMachineNamer().generateNewGroupId(setup);
            setAttribute(LOAD_BALANCER_NAME, lbName);
        }
        createLoadBalancer(lbName);
    }

    protected void createLoadBalancer(String lbName) {
        LOG.info("Creating load balancer {} ({}), in {}", new Object[] {lbName, this, loc});

        Integer loadBalancerPort = getAttribute(PROXY_HTTP_PORT);
        if (loadBalancerPort == null) loadBalancerPort = getRequiredConfig(PROXY_HTTP_PORT).iterator().next();
        String publicIPId = getRequiredConfig(PUBLIC_IP_ID);
        Algorithm algorithm = Algorithm.fromValue(getRequiredConfig(ALGORITHM));
        int instancePort = getRequiredConfig(INSTANCE_PORT);

        String description = getConfig(DESCRIPTION);
        Set<String> allowedSourceCIRDs = getConfig(ALLOWED_SOURCE_CIDRs);
        String domainId = getConfig(DOMAIN_ID);
        String zoneId = getConfig(ZONE_ID);
        String accountInDomain = getConfig(ACCOUNT_IN_DOMAIN);
        Boolean openFirewall = getConfig(OPEN_FIREWALL);
        PublicIPAddress ip = client.getCloudstackGlobalClient().getAddressApi().getPublicIPAddress(publicIPId);

        CreateLoadBalancerRuleOptions options = new CreateLoadBalancerRuleOptions();
        if (description != null) options.description(description);
        if (allowedSourceCIRDs != null) options.allowedSourceCIDRs(allowedSourceCIRDs);
        if (domainId != null) options.domainId(domainId);
        if (zoneId != null) options.zoneId(zoneId);
        if (accountInDomain != null) {
            if (domainId == null) throw new IllegalArgumentException("Domain id must be specified if specifying account ("+accountInDomain+") for " + this);
            options.accountInDomain(accountInDomain, domainId);
        }
        if (openFirewall != null) options.openFirewall(openFirewall);

        // FIXME jclouds javadoc is wrong: it's returning the job id rather than the rule
        String jobId = loadBalancerApi.createLoadBalancerRuleForPublicIP(publicIPId, algorithm, lbName, instancePort, loadBalancerPort, options);
        AsyncJob<Object> job = client.waitForJobSuccess(jobId);
        LoadBalancerRule rule = (LoadBalancerRule) job.getResult();
        String loadBalancerId = rule.getId();

        setAttribute(LOAD_BALANCER_ID, loadBalancerId);
        setAttribute(PROXY_HTTP_PORT, loadBalancerPort);
        setAttribute(HOSTNAME, ip.getIPAddress());
        setAttribute(PROTOCOL, inferProtocol());
        setAttribute(ROOT_URL, inferUrl());
    }

    @Override
    public void deleteLoadBalancer() {
        String lbId = getAttribute(LOAD_BALANCER_ID);
        LOG.info("Deleting load balancer {} ({}, in {})", new Object[] {lbId, this, loc});

        loadBalancerApi.deleteLoadBalancerRule(lbId);
    }

    protected String getZoneId(JcloudsLocation loc) {
        String zoneId = getConfig(ZONE_ID);
        if (zoneId == null) {
            zoneId = loc.getRegion();
        }
        return Preconditions.checkNotNull(zoneId, "zoneId");
    }

    protected <T> T getRequiredConfig(ConfigKey<T> key) {
        return checkNotNull(getConfig(key), key.getName());
    }

    protected <T> T getRequiredConfig(HasConfigKey<T> key) {
        return checkNotNull(getConfig(key), key.getConfigKey().getName());
    }

}
