package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import javax.annotation.Nullable;
import javax.xml.bind.JAXBElement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vmware.vcloud.api.rest.schema.GatewayFeaturesType;
import com.vmware.vcloud.api.rest.schema.GatewayInterfaceType;
import com.vmware.vcloud.api.rest.schema.GatewayNatRuleType;
import com.vmware.vcloud.api.rest.schema.IpRangeType;
import com.vmware.vcloud.api.rest.schema.IpRangesType;
import com.vmware.vcloud.api.rest.schema.NatRuleType;
import com.vmware.vcloud.api.rest.schema.NatServiceType;
import com.vmware.vcloud.api.rest.schema.NetworkServiceType;
import com.vmware.vcloud.api.rest.schema.ObjectFactory;
import com.vmware.vcloud.api.rest.schema.ReferenceType;
import com.vmware.vcloud.api.rest.schema.SubnetParticipationType;
import com.vmware.vcloud.sdk.ReferenceResult;
import com.vmware.vcloud.sdk.Task;
import com.vmware.vcloud.sdk.VCloudException;
import com.vmware.vcloud.sdk.VcloudClient;
import com.vmware.vcloud.sdk.Vdc;
import com.vmware.vcloud.sdk.admin.EdgeGateway;
import com.vmware.vcloud.sdk.admin.extensions.ExtensionQueryService;
import com.vmware.vcloud.sdk.admin.extensions.VcloudAdminExtension;
import com.vmware.vcloud.sdk.constants.Version;
import com.vmware.vcloud.sdk.constants.query.QueryReferenceType;

import com.google.common.annotations.Beta;
import com.google.common.base.Objects;
import com.google.common.base.Predicates;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;

import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.location.basic.PortRanges;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.net.Protocol;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;

/**
 * For adding/removing NAT rules to vcloud-director.
 * <p/>
 * The threading model is that internally all access (to update DNAT rules) is synchronized.
 * The mutex to synchronize on can be passed in (see {@link NatService.Builder#mutex(Object)}).
 * The intent is that the same mutex be passed in for everything accessing the same Edge Gateway.
 * It is extremely important to synchronize because adding NAT rule involves:
 * <ol>
 * <li>download existing NAT rules;
 * <li>add new rule to collection;
 * <li>upload all NAT rules.
 * </ol>
 * Therefore if two threads execute concurrently we may not get both new NAT rules in the resulting uploaded set!
 */
@Beta
public class NatService {

    // TODO Given a vDC identifier, how do we tell which EdgeGateway that corresponds to?

    private static final Logger LOG = LoggerFactory.getLogger(NatService.class);

    private static final List<Version> VCLOUD_VERSIONS = ImmutableList.of(Version.V5_5, Version.V5_1, Version.V1_5);

    public static Builder builder() {
        return new NatServiceFactory().builder();
    }

    public static class NatServiceFactory {
        public Builder builder() {
            return new Builder();
        }
    }

    public static class Builder {
        private String identity;
        private String credential;
        private String endpoint;
        private String vDC;
        private String trustStore;
        private String trustStorePassword;
        private PortRange portRange = PortRanges.ANY_HIGH_PORT;
        private Level logLevel;
        private Object mutex = new Object();

        public Builder identity(String val) {
            this.identity = val;
            return this;
        }

        public Builder credential(String val) {
            this.credential = val;
            return this;
        }

        public Builder endpoint(String val) {
            this.endpoint = checkNotNull(val, "endpoint");
            return this;
        }

        public Builder vDC(@Nullable String val) {
            this.vDC = val;
            return this;
        }

        public Builder trustStore(String val) {
            this.trustStore = val;
            return this;
        }

        public Builder trustStorePassword(String val) {
            this.trustStorePassword = val;
            return this;
        }

        public Builder logLevel(java.util.logging.Level val) {
            this.logLevel = val;
            return this;
        }

        public Builder portRange(PortRange val) {
            portRange = val;
            return this;
        }

        public Builder mutex(Object mutex) {
            this.mutex = checkNotNull(mutex, "mutex");
            return this;
        }

        public NatService build() {
            return new NatService(this);
        }
    }

    public static class Delta {
        protected final List<PortForwardingConfig> toOpen = Lists.newArrayList();
        protected final List<PortForwardingConfig> toClose = Lists.newArrayList();

        public Delta() {
        }

        public Delta toOpen(Iterable<PortForwardingConfig> vals) {
            Iterables.addAll(toOpen, vals);
            return this;
        }

        public Delta toOpen(PortForwardingConfig val) {
            toOpen.add(val);
            return this;
        }

        public Delta toClose(Iterable<PortForwardingConfig> vals) {
            Iterables.addAll(toClose, vals);
            return this;
        }

        public Delta toClose(PortForwardingConfig val) {
            toClose.add(val);
            return this;
        }

        public void checkValid() {
            for (PortForwardingConfig arg : toOpen) {
                arg.checkValid();
            }
            for (PortForwardingConfig arg : toClose) {
                arg.checkValid();
            }
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("toOpen", toOpen).add("toClose", toClose).toString();
        }

        public boolean isEmpty() {
            return toOpen.isEmpty() && toClose.isEmpty();
        }
    }

    /**
     * The result of opening/closing NatServices. Gives a mapping from the args asked for to
     * the actual port mappings created/deleted. The input args could include port-ranges
     * (leaving it up to the impl to choose the actual public port), so the input/output in
     * the map can be different.
     */
    public static class UpdateResult {
        private final Map<PortForwardingConfig, PortForwardingConfig> opened = Maps.newLinkedHashMap();
        private final Map<PortForwardingConfig, PortForwardingConfig> closed = Maps.newLinkedHashMap();

        public UpdateResult() {
        }

        protected UpdateResult opened(PortForwardingConfig req, PortForwardingConfig val) {
            opened.put(req, val);
            return this;
        }

        protected UpdateResult closed(PortForwardingConfig req, PortForwardingConfig val) {
            closed.put(req, val);
            return this;
        }

        public Map<PortForwardingConfig, PortForwardingConfig> getOpened() {
            return opened;
        }

        public Map<PortForwardingConfig, PortForwardingConfig> getClosed() {
            return closed;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("opened", opened.values()).add("closed", closed.values()).toString();
        }
    }

    private final String baseUrl; // e.g. "https://p5v1-vcd.vchs.vmware.com:443";
    @Nullable
    private final String vDC;
    private final String credential;
    private final String identity;
    private final String trustStore;
    private final String trustStorePassword;
    private final PortRange portRange;
    private final Level logLevel;
    private final Object mutex;

    private volatile VcloudClient client;

    public NatService(Builder builder) {
        baseUrl = checkNotNull(builder.endpoint, "endpoint");
        vDC = builder.vDC;
        identity = checkNotNull(builder.identity, "identity");
        credential = checkNotNull(builder.credential, "credential");
        trustStore = builder.trustStore;
        trustStorePassword = builder.trustStorePassword;
        this.portRange = builder.portRange;
        logLevel = builder.logLevel;
        mutex = checkNotNull(builder.mutex, "mutex");
        client = newVcloudClient();
    }

    public HostAndPort openPortForwarding(PortForwardingConfig args) throws VCloudException {
        return openPortForwarding(ImmutableList.of(args)).get(0);
    }

    public List<HostAndPort> openPortForwarding(Iterable<PortForwardingConfig> args) throws VCloudException {
        UpdateResult opened = updatePortForwarding(new Delta().toOpen(args));

        List<HostAndPort> result = Lists.newArrayList();
        for (PortForwardingConfig arg : args) {
            PortForwardingConfig argResult = opened.getOpened().get(arg);
            if (argResult == null) {
                LOG.warn("No result for requested OPEN: " + arg + " (results: " + opened.getOpened() + ")");
                result.add(null);
            } else {
                result.add(argResult.publicEndpoint);
            }
        }
        return result;
    }

    public HostAndPort closePortForwarding(PortForwardingConfig args) throws VCloudException {
        return closePortForwarding(ImmutableList.of(args)).get(0);
    }

    public List<HostAndPort> closePortForwarding(Iterable<PortForwardingConfig> args) throws VCloudException {
        UpdateResult closed = updatePortForwarding(new Delta().toClose(args));

        List<HostAndPort> result = Lists.newArrayList();
        for (PortForwardingConfig arg : args) {
            PortForwardingConfig argResult = closed.getClosed().get(arg);
            if (argResult == null) {
                LOG.warn("No result for requested CLOSE: " + arg + " (results: " + closed.getClosed() + ")");
                result.add(null);
            } else {
                result.add(argResult.publicEndpoint);
            }
        }
        return result;
    }

    /**
     * @return The changes made (in the same order as the delta passed in); important for if the
     * delta left port-choice up to the implementation for example.
     */
    public UpdateResult updatePortForwarding(Delta delta) throws VCloudException {
        final int MAX_CONSECUTIVE_FORBIDDEN_REQUESTS = 10;

        if (delta.isEmpty()) new Delta();

        delta.checkValid();
        if (LOG.isDebugEnabled()) LOG.debug("Updating port forwarding at {}: {}", baseUrl, delta);

        int consecutiveForbiddenCount = 0;
        int iteration = 0;
        do {
            iteration++;
            try {
                return updatePortForwardingImpl(delta);
            } catch (VCloudException e) {
                // If the EdgeGateway is being reconfigured by someone else, then the update operation will fail.
                // In that situation, retry (from the beginning - retrieve all rules again in case they are
                // different from last time). We've seen it regularly take 45 seconds to reconfigure the
                // EdgeGateway (to add/remove a NAT rule), so be patient!
                // 
                // TODO Don't hard-code exception messages - dangerous for internationalisation etc.
                if (e.toString().contains("is busy completing an operation")) {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Retrying after iteration {} failed (server busy), updating port forwarding at {}: {} - {}",
                                new Object[]{iteration, baseUrl, delta, e});
                    consecutiveForbiddenCount = 0;
                } else if (e.toString().contains("Access is forbidden")) {
                    // See https://issues.apache.org/jira/browse/BROOKLYN-116
                    consecutiveForbiddenCount++;
                    if (consecutiveForbiddenCount > MAX_CONSECUTIVE_FORBIDDEN_REQUESTS) {
                        throw e;
                    } else {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Retrying after iteration {} failed (access is forbidden - {} consecutive), updating port forwarding at {}: {} - {}",
                                    new Object[]{iteration, consecutiveForbiddenCount, baseUrl, delta, e});
                        synchronized (getMutex()) {
                            client = newVcloudClient();
                        }
                        Duration initialDelay = Duration.millis(10);
                        Duration delay = initialDelay.multiply(Math.pow(1.2, consecutiveForbiddenCount - 1));
                        Time.sleep(delay);
                    }
                } else {
                    throw e;
                }
            }
        } while (true);
    }

    private UpdateResult updatePortForwardingImpl(Delta delta) throws VCloudException {
        // Append DNAT rule to NAT service; retrieve the existing, modify it, and upload.
        // If instead we create new objects then risk those having different config - this is *not* a delta!

        Set<String> publicIps = Sets.newLinkedHashSet();
        for (PortForwardingConfig arg : delta.toOpen) {
            publicIps.add(arg.publicEndpoint.getHostText());
        }
        for (PortForwardingConfig arg : delta.toClose) {
            publicIps.add(arg.publicEndpoint.getHostText());
        }

        UpdateResult result = new UpdateResult();
        long timeRetrieve, timePrepare, timeReconfigure, timeChecking;
        Stopwatch stopwatch = Stopwatch.createStarted();

        synchronized (getMutex()) {
            EdgeGateway edgeGateway = getEdgeGatewayForPublicIp(publicIps);
            GatewayFeaturesType gatewayFeatures = getGatewayFeatures(edgeGateway);
            NatServiceType natService = tryFindService(gatewayFeatures.getNetworkService(), NatServiceType.class).get();
            Map<String, GatewayInterfaceType> publicIpToGatewayInterface = getUplinkGatewayInterfacePerPublicIp(edgeGateway, publicIps);

            timeRetrieve = stopwatch.elapsed(TimeUnit.MILLISECONDS);

            // generate and add the new rules
            for (PortForwardingConfig arg : delta.toOpen) {
                // If not given a public port explicitly, then find one that is not in use
                // TODO If many NAT rules to be opened in the batch, then this is inefficient - but nothing compared to
                // the time it takes VMware to make the change (e.g. 40 seconds).
                HostAndPort publicEndpoint = arg.publicEndpoint;
                GatewayInterfaceType gatewayInterface = publicIpToGatewayInterface.get(publicEndpoint.getHostText());
                ReferenceType externalNetworkRef = gatewayInterface.getNetwork();

                if (arg.publicEndpoint.hasPort()) {
                    publicEndpoint = arg.publicEndpoint;
                } else {
                    // FIXME: Allow port range to be passed in to REST API
                    // This is a temporary workaround, until Brooklyn has been updated to pass null port range by default
                    PortRange portRangeToUse = portRange;
                    Set<Integer> usedPorts = getUsedPublicPorts(Iterables.filter(natService.getNatRule(), Predicates.and(
                            NatPredicates.protocolMatches(arg.protocol),
                            NatPredicates.originalIpEquals(arg.publicEndpoint.getHostText()))));
                    int availablePublicPort = findAvailablePort(portRangeToUse, usedPorts);
                    publicEndpoint = HostAndPort.fromParts(arg.publicEndpoint.getHostText(), availablePublicPort);
                }

                GatewayNatRuleType gatewayNatRule = generateGatewayNatRule(
                        arg.protocol,
                        publicEndpoint,
                        arg.targetEndpoint,
                        externalNetworkRef);
                NatRuleType dnatRule = generateDnatRule(true, gatewayNatRule);

                natService.getNatRule().add(dnatRule);

                result.opened(arg, new PortForwardingConfig()
                        .protocol(arg.protocol)
                        .publicEndpoint(publicEndpoint)
                        .targetEndpoint(arg.targetEndpoint));
            }

            // Remove the closed rules
            for (PortForwardingConfig arg : delta.toClose) {
                // TODO Could also match on networkId
                Iterable<NatRuleType> filtered = Iterables.filter(natService.getNatRule(), Predicates.and(
                        NatPredicates.protocolMatches(arg.protocol),
                        NatPredicates.originalEndpointEquals(arg.publicEndpoint.getHostText(), arg.publicEndpoint.getPort()),
                        NatPredicates.translatedEndpointEquals(arg.targetEndpoint.getHostText(), arg.targetEndpoint.getPort())));
                natService.getNatRule().removeAll(Lists.newArrayList(filtered));

                result.closed(arg, arg);
            }

            // Create a minimal gateway-feature-set to be reconfigured (i.e. just the NAT Service)
            GatewayFeaturesType modifiedGatewayFeatures = new GatewayFeaturesType();
            JAXBElement<NetworkServiceType> modifiedNatService = new ObjectFactory().createNetworkService(natService);
            modifiedGatewayFeatures.getNetworkService().add(modifiedNatService);

            timePrepare = stopwatch.elapsed(TimeUnit.MILLISECONDS) - timeRetrieve;

            // Execute task (i.e. make the actual change, and wait for completion)
            Task task = edgeGateway.configureServices(modifiedGatewayFeatures);
            waitForTask(task, "update NAT rules");
            timeReconfigure = stopwatch.elapsed(TimeUnit.MILLISECONDS) - timePrepare;

            // Confirm the updates have been applied.
            // Retrieves a new EdgeGateway instance, to ensure we're not just looking at our local copy.
            List<NatRuleType> rules = getNatRules(EdgeGateway.getEdgeGatewayByReference(client, edgeGateway.getReference()));

            // Confirm that the newly created rules exist,
            // with the expected translated (i.e internal) and original (i.e. public) addresses,
            // and without any conflicting DNAT rules already using that port.
            for (PortForwardingConfig arg : result.getOpened().values()) {
                Iterable<NatRuleType> matches = Iterables.filter(rules, Predicates.and(
                        NatPredicates.originalEndpointEquals(arg.publicEndpoint.getHostText(), arg.publicEndpoint.getPort()),
                        NatPredicates.translatedEndpointEquals(arg.targetEndpoint.getHostText(), arg.targetEndpoint.getPort())));

                Iterable<NatRuleType> conflicts = Iterables.filter(rules, Predicates.and(
                        NatPredicates.originalEndpointEquals(arg.publicEndpoint.getHostText(), arg.publicEndpoint.getPort()),
                        Predicates.not(NatPredicates.translatedEndpointEquals(arg.targetEndpoint.getHostText(), arg.targetEndpoint.getPort()))));

                if (Iterables.isEmpty(matches)) {
                    throw new IllegalStateException(
                            String.format("Gateway NAT Rules: cannot find translated %s and original %s:%s at %s",
                                    arg.targetEndpoint, arg.publicEndpoint.getHostText(), arg.publicEndpoint.getPort(), baseUrl));
                } else if (Iterables.size(matches) > 1) {
                    LOG.warn(String.format("Gateway NAT Rules: %s duplicates for translated %s and original %s:%s at %s; continuing.",
                            Iterables.size(matches), arg.targetEndpoint, arg.publicEndpoint.getHostText(), arg.publicEndpoint.getPort(), baseUrl));
                }
                if (Iterables.size(conflicts) > 0) {
                    throw new IllegalStateException(
                            String.format("Gateway NAT Rules: original already assigned for translated %s and original %s:%s at %s",
                                    arg.targetEndpoint, arg.publicEndpoint.getHostText(), arg.publicEndpoint.getPort(), baseUrl));
                }
            }

            // Confirm that deleted rules don't exist.
            for (PortForwardingConfig arg : result.getClosed().values()) {
                Iterable<NatRuleType> matches = Iterables.filter(rules, Predicates.and(
                        NatPredicates.protocolMatches(arg.protocol),
                        NatPredicates.originalEndpointEquals(arg.publicEndpoint.getHostText(), arg.publicEndpoint.getPort()),
                        NatPredicates.translatedEndpointEquals(arg.targetEndpoint.getHostText(), arg.targetEndpoint.getPort())));

                if (!Iterables.isEmpty(matches)) {
                    throw new IllegalStateException(
                            String.format("Gateway NAT Rules: the rule with translated %s and original %s:%s at %s has NOT " +
                                    "been deleted", arg.targetEndpoint, arg.publicEndpoint.getHostText(), arg.publicEndpoint.getPort(), baseUrl));
                }
            }

            timeChecking = stopwatch.elapsed(TimeUnit.MILLISECONDS) - timeReconfigure;
        }

        LOG.info("Finished updating NAT rules on {}@{}; took retrieve={}, prepare={}, reconfigure={}, checking={}; mods: {}",
                new Object[]{identity, baseUrl, Time.makeTimeStringRounded(timeRetrieve),
                        Time.makeTimeStringRounded(timePrepare), Time.makeTimeStringRounded(timeReconfigure),
                        Time.makeTimeStringRounded(timeChecking), delta});
        return result;
    }

    public Set<Integer> getUsedPublicPorts(Iterable<NatRuleType> existingRules) {
        Set<Integer> result = Sets.newHashSet();
        for (NatRuleType rule : existingRules) {
            if (rule.getGatewayNatRule() != null) {
                try {
                    result.add(Integer.parseInt(rule.getGatewayNatRule().getOriginalPort()));
                } catch (NumberFormatException e) {
                    // e.g. could be "any"
                    LOG.debug("Gateway NAT Rule " + rule + " original port is not a number; ignoring");
                }
            }
        }
        return result;
    }

    public int findAvailablePort(PortRange portRange, Collection<Integer> usedPorts) {
        for (int port : portRange) {
            if (!usedPorts.contains(port)) return port;
        }
        throw new IllegalStateException("No free ports in range " + portRange);
    }

    public List<NatRuleType> getNatRules() throws VCloudException {
        // Append DNAT rule to NAT service; retrieve the existing, modify it, and upload.
        // If instead we create new objects then risk those having different config - this is *not* a delta!

        List<NatRuleType> result = Lists.newArrayList();
        synchronized (getMutex()) {
            for (EdgeGateway edgeGateway : getEdgeGateways()) {
                result.addAll(getNatRules(edgeGateway));
            }
        }
        return result;
    }

    public void enableNatService() throws VCloudException {
        if (LOG.isDebugEnabled()) LOG.debug("Enabling NAT Service at {}", baseUrl);

        synchronized (getMutex()) {
            for (EdgeGateway edgeGateway : getEdgeGateways()) {
                GatewayFeaturesType gatewayFeatures = getGatewayFeatures(edgeGateway);
                Maybe<NatServiceType> natService = tryFindService(gatewayFeatures.getNetworkService(), NatServiceType.class);

                if (natService.isPresent()) {
                    LOG.info("Enabling NAT Service for {} @ {}, EdgeGateway {}", new Object[]{identity, baseUrl, edgeGateway});

                    // Modify
                    natService.get().setIsEnabled(true);

                    // Execute task
                    Task task = edgeGateway.configureServices(gatewayFeatures);
                    waitForTask(task, "enable nat-service");
                }
            }
        }
    }

    protected Object getMutex() {
        return mutex;
    }

    protected void waitForTask(Task task, String summary) throws VCloudException {
        checkNotNull(task, "task null for %s", summary);
        try {
            /*
                Occasionally when deploying to a vcloud-air target, the following line would hang for an extremely
                long time when getting the http response in the http code.
                The issue appears to be related to https://issues.apache.org/jira/browse/BROOKLYN-106
                If the resolution to BROOKLYN-106 does not resolve the issue of the following line hanging, then
                an alternative workaround would be to put the call to waitForTask(2*60*1000) inside a thread so that
                we can time it out ourselves, with a while loop around it for retry
             */
            task.waitForTask(0);
        } catch (TimeoutException e) {
            throw Exceptions.propagate(e);
        }
    }

    private boolean includesPublicIp(final String publicIp, List<SubnetParticipationType> subnetParticipations) {
        if (subnetParticipations == null) return false;

        for (SubnetParticipationType subnetParticipation : subnetParticipations) {
            if (subnetParticipation.getIpRanges() != null && subnetParticipation.getIpRanges().getIpRange() != null) {
                for (IpRangeType ipRangeType : subnetParticipation.getIpRanges().getIpRange()) {
                    long ipLo = ipToLong(InetAddresses.forString(ipRangeType.getStartAddress()));
                    long ipHi = ipToLong(InetAddresses.forString(ipRangeType.getEndAddress()));
                    long ipToTest = ipToLong(InetAddresses.forString(publicIp));
                    if (ipToTest >= ipLo && ipToTest <= ipHi) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void checkPublicIps(Iterable<String> publicIps, List<SubnetParticipationType> subnetParticipations) {
        for (String publicIp : publicIps) {
            checkPublicIp(publicIp, subnetParticipations);
        }
    }

    private void checkPublicIp(final String publicIp, List<SubnetParticipationType> subnetParticipations) {
        boolean found = includesPublicIp(publicIp, subnetParticipations);
        if (!found) {
            StringBuilder builder = new StringBuilder();
            builder.append("PublicIp '" + publicIp + "' is not valid. Public IP must fall in the following ranges: ");
            if (subnetParticipations == null) {
                for (SubnetParticipationType subnetParticipation : subnetParticipations) {
                    IpRangesType range = subnetParticipation.getIpRanges();
                    if (range != null && range.getIpRange() != null) {
                        for (IpRangeType ipRangeType : range.getIpRange()) {
                            builder.append(ipRangeType.getStartAddress());
                            builder.append(" - ");
                            builder.append(ipRangeType.getEndAddress());
                            builder.append(", ");
                        }
                    } else {
                        builder.append("<no ip range>, ");
                    }
                }
            } else {
                builder.append("<no subnet participants>");
            }
            LOG.error(builder.toString() + " (rethrowing)");
            throw new IllegalArgumentException(builder.toString());
        }
    }

    private static long ipToLong(InetAddress ip) {
        byte[] octets = ip.getAddress();
        long result = 0;
        for (byte octet : octets) {
            result <<= 8;
            result |= octet & 0xff;
        }
        return result;
    }

    protected List<EdgeGateway> getEdgeGateways() throws VCloudException {
        List<EdgeGateway> result = Lists.newArrayList();

        if (vDC != null) {
            ReferenceType vDCRef = new ReferenceType();
            vDCRef.setHref(vDC);
            for (ReferenceType edgeGatewayRef : Vdc.getVdcByReference(client, vDCRef).getEdgeGatewayRefs().getReferences()) {
                result.add(EdgeGateway.getEdgeGatewayByReference(client, edgeGatewayRef));
            }
        } else {
            for (ReferenceType ref : queryEdgeGateways()) {
                result.add(EdgeGateway.getEdgeGatewayById(client, ref.getId()));
            }
        }
        return result;
    }

    protected EdgeGateway getEdgeGatewayForPublicIp(Iterable<String> publicIps) throws VCloudException {
        Map<EdgeGateway, String> errs = Maps.newLinkedHashMap();

        for (EdgeGateway edgeGateway : getEdgeGateways()) {
            // extract the EdgeGateway's subnet participations to validate public IPs
            List<SubnetParticipationType> subnetParticipations = Lists.newArrayList();
            for (GatewayInterfaceType gatewayInterfaceType : getUplinkGatewayInterfaces(edgeGateway)) {
                subnetParticipations.addAll(gatewayInterfaceType.getSubnetParticipation());
            }
            try {
                checkPublicIps(publicIps, subnetParticipations);
                return edgeGateway;
            } catch (IllegalArgumentException e) {
                errs.put(edgeGateway, e.getMessage());
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("No EdgeGateway for publicIps " + publicIps + ":\n");
        for (Map.Entry<EdgeGateway, String> entry : errs.entrySet()) {
            builder.append("\t");
            builder.append("edgeGateway=" + entry.getKey().getReference().getId() + "; " + entry.getValue());
            builder.append("\n");
        }
        String err = builder.toString();
        throw new IllegalArgumentException(err);
    }

    private Map<String, GatewayInterfaceType> getUplinkGatewayInterfacePerPublicIp(EdgeGateway edgeGateway, Iterable<String> publicIps) {
        Map<String, GatewayInterfaceType> result = Maps.newLinkedHashMap();
        List<GatewayInterfaceType> gatewayInterfaces = getUplinkGatewayInterfaces(edgeGateway);
        for (String publicIp : publicIps) {
            boolean found = false;
            for (GatewayInterfaceType gatewayInterface : gatewayInterfaces) {
                if (includesPublicIp(publicIp, gatewayInterface.getSubnetParticipation())) {
                    result.put(publicIp, gatewayInterface);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalStateException("No Gateway Interface for public IP " + publicIp + " in edge gateway " + edgeGateway.getReference().getId());
            }
        }
        return result;
    }

    // extract the external network attached to the EdgeGateway
    private List<GatewayInterfaceType> getUplinkGatewayInterfaces(EdgeGateway edgeGateway) {
        List<GatewayInterfaceType> result = Lists.newArrayList();
        for (GatewayInterfaceType gatewayInterfaceType : edgeGateway.getResource().getConfiguration().getGatewayInterfaces().getGatewayInterface()) {
            if (gatewayInterfaceType.getInterfaceType().equals("uplink")) {
                result.add(gatewayInterfaceType);
            }
        }
        return result;
    }


    protected GatewayFeaturesType getGatewayFeatures(EdgeGateway edgeGateway) {
        return edgeGateway
                .getResource()
                .getConfiguration()
                .getEdgeGatewayServiceConfiguration();
    }

    protected List<JAXBElement<? extends NetworkServiceType>> getNetworkServices(EdgeGateway edgeGateway) {
        return getGatewayFeatures(edgeGateway).getNetworkService();
    }

    protected <T extends NetworkServiceType> Maybe<T> tryFindService(List<JAXBElement<? extends NetworkServiceType>> services, Class<T> type) {
        for (JAXBElement<? extends NetworkServiceType> service : services) {
            if (service.getDeclaredType().getSimpleName().equals(type.getSimpleName())) {
                return Maybe.of(type.cast(service.getValue()));
            }
        }
        return Maybe.absent("No service of type " + type.getSimpleName());
    }

    protected List<NatRuleType> getNatRules(EdgeGateway edgeGateway) {
        List<JAXBElement<? extends NetworkServiceType>> services = getNetworkServices(edgeGateway);
        Maybe<NatServiceType> natService = tryFindService(services, NatServiceType.class);
        return (natService.isPresent()) ? natService.get().getNatRule() : new ArrayList<NatRuleType>();
    }

    private List<ReferenceType> queryEdgeGateways() throws VCloudException {
        // Getting the VcloudAdminExtension
        VcloudAdminExtension adminExtension = client.getVcloudAdminExtension();

        // Getting the Admin Extension Query Service.
        ExtensionQueryService queryService = adminExtension.getExtensionQueryService();
        ReferenceResult referenceResult = queryService.queryReferences(QueryReferenceType.EDGEGATEWAY);
        return referenceResult.getReferences();
    }

    protected VcloudClient newVcloudClient() {
        return newVcloudClient(baseUrl, identity, credential, trustStore, trustStorePassword, logLevel);
    }

    // FIXME Don't set sysprop as could affect all other activities of the JVM!
    protected VcloudClient newVcloudClient(String endpoint, String identity, String credential, String trustStore, String trustStorePassword, Level logLevel) {
        try {
            if (logLevel != null) {
                // Logging is extremely verbose at INFO - it logs in full every http request/response (including payload).
                // Consider setting this to WARN; leaving as default is not explicitly set
                VcloudClient.setLogLevel(logLevel);
            }

            // The vcloudClient want the URI without the path.
            // However, users of the NatMicroserviceClient may want the URI to include
            // the vOrg in the path, because some endpoints are only accessible in that way.
            URI uri = URI.create(endpoint);
            String vCloudUrl = new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null).toString();

            // Client login
            VcloudClient vcloudClient = null;
            boolean versionFound = false;
            for (Version version : VCLOUD_VERSIONS) {
                try {
                    vcloudClient = new VcloudClient(vCloudUrl, version);
                    LOG.debug("VCloudClient - trying login to {} using {}", endpoint, version);
                    vcloudClient.login(identity, credential);
                    versionFound = true;
                    LOG.info("VCloudClient - Logged into {} using version {}", endpoint, version);
                    break;
                } catch (VCloudException e) {
                    LOG.debug("VCloudClient - Cannot login to " + endpoint + " using " + version, e);
                }
            }
            if (!versionFound) {
                throw new IllegalStateException("Cannot login to " + endpoint + " using any of " + VCLOUD_VERSIONS);
            }

            // Performing Certificate Validation
            if (Strings.isNonBlank(trustStore)) {
                System.setProperty("javax.net.ssl.trustStore", trustStore);
                System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
                vcloudClient.registerScheme("https", 443, CustomSSLSocketFactory.getInstance());

            } else {
                LOG.warn("Ignoring the Certificate Validation using FakeSSLSocketFactory");
                vcloudClient.registerScheme("https", 443, FakeSSLSocketFactory.getInstance());
            }
            return vcloudClient;
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    private GatewayNatRuleType generateGatewayNatRule(Protocol protocol, HostAndPort original,
                                                      HostAndPort translated, ReferenceType interfaceRef) {
        GatewayNatRuleType gatewayNatRule = new GatewayNatRuleType();
        gatewayNatRule.setProtocol(protocol.toString());
        gatewayNatRule.setOriginalIp(original.getHostText());
        gatewayNatRule.setOriginalPort(Integer.toString(original.getPort()));
        gatewayNatRule.setTranslatedIp(translated.getHostText());
        gatewayNatRule.setTranslatedPort(Integer.toString(translated.getPort()));
        gatewayNatRule.setInterface(interfaceRef);
        return gatewayNatRule;
    }

    private NatRuleType generateDnatRule(boolean enabled, GatewayNatRuleType gatewayNatRule) {
        NatRuleType dnatRule = new NatRuleType();
        dnatRule.setIsEnabled(enabled);
        dnatRule.setRuleType("DNAT");
        dnatRule.setGatewayNatRule(gatewayNatRule);
        return dnatRule;
    }
}
