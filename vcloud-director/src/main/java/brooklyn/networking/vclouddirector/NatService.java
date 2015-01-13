package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import javax.xml.bind.JAXBElement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.guava.Maybe;
import brooklyn.util.net.Protocol;
import brooklyn.util.time.Duration;
import brooklyn.util.time.Time;

import com.google.common.annotations.Beta;
import com.google.common.base.Objects;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import com.vmware.vcloud.api.rest.schema.GatewayFeaturesType;
import com.vmware.vcloud.api.rest.schema.GatewayInterfaceType;
import com.vmware.vcloud.api.rest.schema.GatewayNatRuleType;
import com.vmware.vcloud.api.rest.schema.IpRangeType;
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
import com.vmware.vcloud.sdk.admin.EdgeGateway;
import com.vmware.vcloud.sdk.admin.extensions.ExtensionQueryService;
import com.vmware.vcloud.sdk.admin.extensions.VcloudAdminExtension;
import com.vmware.vcloud.sdk.constants.Version;
import com.vmware.vcloud.sdk.constants.query.QueryReferenceType;

/**
 * For adding/removing NAT rules to vcloud-director.
 * 
 * The threading model is that internally all access (to update DNAT rules) is synchronized.
 * The mutex to synchronize on can be passed in (see {@link NatService.Builder#mutex(Object)}).
 * The intent is that the same mutex be passed in for everything accessing the same Edge Gateway.
 * It is extremely important to synchronize because adding NAT rule involves:
 * <ol>
 *   <li>download existing NAT rules;
 *   <li>add new rule to collection;
 *   <li>upload all NAT rules.
 * </ol>
 * Therefore if two threads execute concurrently we may not get both new NAT rules in the resulting uploaded set!
 */
@Beta
public class NatService {

    private static final Logger LOG = LoggerFactory.getLogger(NatService.class);
    
    private static final List<Version> VCLOUD_VERSIONS = ImmutableList.of(Version.V5_5, Version.V5_1, Version.V1_5);

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String identity;
        private String credential;
        private String endpoint;
        private String trustStore;
        private String trustStorePassword;
        private Level logLevel;
        private Object mutex = new Object();
        
        public Builder identity(String val) {
            this.identity = val; return this;
        }
        public Builder credential(String val) {
            this.credential = val; return this;
        }
        public Builder endpoint(String val) {
            this.endpoint = checkNotNull(val, "endpoint"); return this;
        }
        public Builder trustStore(String val) {
            this.trustStore = val; return this;
        }
        public Builder trustStorePassword(String val) {
            this.trustStorePassword = val; return this;
        }
        public Builder logLevel(java.util.logging.Level val) {
            this.logLevel = val; return this;
        }
        public Builder mutex(Object mutex) {
            this.mutex = checkNotNull(mutex, "mutex"); return this;
        }
        public NatService build() {
            return new NatService(this);
        }
    }
    
    public static class Delta {
        private final List<PortForwardingConfig> toClose = Lists.newArrayList();
        private final List<PortForwardingConfig> toOpen = Lists.newArrayList();
        
        public Delta() {
        }
        public Delta toOpen(Iterable<PortForwardingConfig> vals) {
            Iterables.addAll(toOpen, vals); return this;
        }
        public Delta toClose(Iterable<PortForwardingConfig> vals) {
            Iterables.addAll(toClose, vals); return this;
        }
        public void checkValid() {
            for (PortForwardingConfig arg : toOpen) {
                arg.checkValid();
            }
            for (PortForwardingConfig arg : toClose) {
                arg.checkValid();
            }
        }
        @Override public String toString() {
            return Objects.toStringHelper(this).add("toOpen", toOpen).add("toClose", toClose).toString();
        }
        public boolean isEmpty() {
            return toOpen.isEmpty() && toClose.isEmpty();
        }
    }
    
    private final String baseUrl; // e.g. "https://p5v1-vcd.vchs.vmware.com:443";
    private final String credential;
    private final String identity;
    private final String trustStore;
    private final String trustStorePassword;
    private final Level logLevel;
    private final Object mutex;

    private volatile VcloudClient client;

    public NatService(Builder builder) {
        baseUrl = checkNotNull(builder.endpoint, "endpoint");
        identity = checkNotNull(builder.identity, "identity");
        credential = checkNotNull(builder.credential, "credential");
        trustStore = builder.trustStore;
        trustStorePassword = builder.trustStorePassword;
        logLevel = builder.logLevel;
        mutex = checkNotNull(builder.mutex, "mutex");
        client = newVcloudClient();
    }

    public HostAndPort openPortForwarding(PortForwardingConfig args) throws VCloudException {
        return openPortForwarding(ImmutableList.of(args)).get(0);
    }
    
    public List<HostAndPort> openPortForwarding(Iterable<PortForwardingConfig> args) throws VCloudException {
        updatePortForwarding(new Delta().toOpen(args));
        
        List<HostAndPort> result = Lists.newArrayList();
        for (PortForwardingConfig arg : args) {
            result.add(HostAndPort.fromParts(arg.publicIp, arg.publicPort));
        }
        return result;
    }

    public HostAndPort closePortForwarding(PortForwardingConfig args) throws VCloudException {
        return closePortForwarding(ImmutableList.of(args)).get(0);
    }
    
    public List<HostAndPort> closePortForwarding(Iterable<PortForwardingConfig> args) throws VCloudException {
        updatePortForwarding(new Delta().toClose(args));
        
        List<HostAndPort> result = Lists.newArrayList();
        for (PortForwardingConfig arg : args) {
            result.add(HostAndPort.fromParts(arg.publicIp, arg.publicPort));
        }
        return result;
    }
    
    public void updatePortForwarding(Delta delta) throws VCloudException {
        final int MAX_CONSECUTIVE_FORBIDDEN_REQUESTS = 10;
        
        if (delta.isEmpty()) return;
        
        delta.checkValid();
        if (LOG.isDebugEnabled()) LOG.debug("Updating port forwarding at {}: {}", baseUrl, delta);

        int consecutiveForbiddenCount = 0;
        int iteration = 0;
        do {
            iteration++;
            try {
                updatePortForwardingImpl(delta);
                return;
            } catch (VCloudException e) {
                // If the EdgeGateway is being reconfigured by someone else, then the update operation will fail.
                // In that situation, retry (from the beginning - retrieve all rules again in case they are
                // different from last time). We've seen it regularly take 45 seconds to reconfigure the
                // EdgeGateway (to add/remove a NAT rule), so be patient!
                // 
                // TODO Don't hard-code exception messages - dangerous for internationalisation etc.
                if (e.toString().contains("is busy completing an operation")) {
                    if (LOG.isDebugEnabled()) LOG.debug("Retrying after iteration {} failed (server busy), updating port forwarding at {}: {} - {}", 
                            new Object[] {iteration, baseUrl, delta, e});
                    consecutiveForbiddenCount = 0;
                } else if (e.toString().contains("Access is forbidden")) {
                    // See https://issues.apache.org/jira/browse/BROOKLYN-116
                    consecutiveForbiddenCount++;
                    if (consecutiveForbiddenCount > MAX_CONSECUTIVE_FORBIDDEN_REQUESTS) {
                        throw e;
                    } else {
                        if (LOG.isDebugEnabled()) LOG.debug("Retrying after iteration {} failed (access is forbidden - {} consecutive), updating port forwarding at {}: {} - {}", 
                                new Object[] {iteration, consecutiveForbiddenCount, baseUrl, delta, e});
                        synchronized (getMutex()) {
                            client = newVcloudClient();
                        }
                        Duration initialDelay = Duration.millis(10);
                        Duration delay = initialDelay.multiply(Math.pow(1.2, consecutiveForbiddenCount-1));
                        Time.sleep(delay);
                    }
                } else {
                    throw e;
                }
            }
        } while (true);
    }

    private void updatePortForwardingImpl(Delta delta) throws VCloudException {
        // Append DNAT rule to NAT service; retrieve the existing, modify it, and upload.
        // If instead we create new objects then risk those having different config - this is *not* a delta!

        synchronized (getMutex()) {
            EdgeGateway edgeGateway = getEdgeGateway();
            GatewayFeaturesType gatewayFeatures = getGatewayFeatures(edgeGateway);
            NatServiceType natService = tryFindService(gatewayFeatures.getNetworkService(), NatServiceType.class).get();

            // extract the external network attached to the EdgeGateway and its subnet participations to validate public IP
            ReferenceType externalNetworkRef = null;
            List<SubnetParticipationType> subnetParticipations = Lists.newArrayList();
            for (GatewayInterfaceType gatewayInterfaceType : edgeGateway.getResource().getConfiguration().getGatewayInterfaces().getGatewayInterface()) {
                if (gatewayInterfaceType.getInterfaceType().equals("uplink")) {
                    externalNetworkRef = gatewayInterfaceType.getNetwork();
                    subnetParticipations.addAll(gatewayInterfaceType.getSubnetParticipation());
                }
            }
            Set<String> publicIps = Sets.newLinkedHashSet();
            for (PortForwardingConfig arg : delta.toOpen) {
                publicIps.add(arg.publicIp);
            }
            for (PortForwardingConfig arg : delta.toClose) {
                publicIps.add(arg.publicIp);
            }
            checkPublicIps(publicIps, subnetParticipations);
            
            // generate and add the new rules
            for (PortForwardingConfig arg : delta.toOpen) {
                GatewayNatRuleType gatewayNatRule = generateGatewayNatRule(
                        arg.protocol,
                        HostAndPort.fromParts(arg.publicIp, arg.publicPort),
                        arg.target,
                        externalNetworkRef);
                NatRuleType dnatRule = generateDnatRule(true, gatewayNatRule);
    
                natService.getNatRule().add(dnatRule);
            }
            
            // Remove the closed rules
            for (PortForwardingConfig arg : delta.toClose) {
                // TODO Could also match on networkId
                Iterable<NatRuleType> filtered = Iterables.filter(natService.getNatRule(), Predicates.and(
                        NatPredicates.protocolMatches(arg.protocol),
                        NatPredicates.originalTargetEquals(arg.publicIp, arg.publicPort),
                        NatPredicates.translatedTargetEquals(arg.target.getHostText(), arg.target.getPort())));
                natService.getNatRule().removeAll(Lists.newArrayList(filtered));
            }

            // Create a minimal gateway-feature-set to be reconfigured (i.e. just the NAT Service)
            GatewayFeaturesType modifiedGatewayFeatures = new GatewayFeaturesType();
            JAXBElement<NetworkServiceType> modifiedNatService = new ObjectFactory().createNetworkService(natService);
            modifiedGatewayFeatures.getNetworkService().add(modifiedNatService);

            // Execute task (i.e. make the actual change, and wait for completion)
            Task task = edgeGateway.configureServices(modifiedGatewayFeatures);
            waitForTask(task, "update NAT rules");

            // Confirm the updates have been applied.
            // Retrieves a new EdgeGateway instance, to ensure we're not just looking at our local copy.
            List<NatRuleType> rules = getNatRules(getEdgeGateway());

            // Confirm that the newly created rules exist,
            // with the expected translated (i.e internal) and original (i.e. public) addresses,
            // and without any conflicting DNAT rules already using that port.
            for (PortForwardingConfig arg : delta.toOpen) {
                Iterable<NatRuleType> matches = Iterables.filter(rules, Predicates.and(
                        NatPredicates.originalTargetEquals(arg.publicIp, arg.publicPort),
                        NatPredicates.translatedTargetEquals(arg.target.getHostText(), arg.target.getPort())));
    
                Iterable<NatRuleType> conflicts = Iterables.filter(rules, Predicates.and(
                        NatPredicates.originalTargetEquals(arg.publicIp, arg.publicPort),
                        Predicates.not(NatPredicates.translatedTargetEquals(arg.target.getHostText(), arg.target.getPort()))));
    
                if (Iterables.isEmpty(matches)) {
                    throw new IllegalStateException(
                            String.format("Gateway NAT Rules: cannot find translated %s and original %s:%s at %s",
                                    arg.target, arg.publicIp, arg.publicPort, baseUrl));
                } else if (Iterables.size(matches) > 1) {
                    LOG.warn(String.format("Gateway NAT Rules: %s duplicates for translated %s and original %s:%s at %s; continuing.",
                            Iterables.size(matches), arg.target, arg.publicIp, arg.publicPort, baseUrl));
                }
                if (Iterables.size(conflicts) > 0) {
                    throw new IllegalStateException(
                            String.format("Gateway NAT Rules: original already assigned for translated %s and original %s:%s at %s",
                                    arg.target, arg.publicIp, arg.publicPort, baseUrl));
                }
            }
            
            // Confirm that deleted rules don't exist.
            for (PortForwardingConfig arg : delta.toClose) {
                Iterable<NatRuleType> matches = Iterables.filter(rules, Predicates.and(
                        NatPredicates.protocolMatches(arg.protocol),
                        NatPredicates.originalTargetEquals(arg.publicIp, arg.publicPort),
                        NatPredicates.translatedTargetEquals(arg.target.getHostText(), arg.target.getPort())));
        
                if (!Iterables.isEmpty(matches)) {
                    throw new IllegalStateException(
                            String.format("Gateway NAT Rules: the rule with translated %s and original %s:%s at %s has NOT " +
                                            "been deleted", arg.target, arg.publicIp, arg.publicPort, baseUrl));
                }
            }
        }
    }
    
    public List<NatRuleType> getNatRules() throws VCloudException {
        // Append DNAT rule to NAT service; retrieve the existing, modify it, and upload.
        // If instead we create new objects then risk those having different config - this is *not* a delta!

        synchronized (getMutex()) {
            return getNatRules(getEdgeGateway());
        }
    }

    public void enableNatService() throws VCloudException {
        if (LOG.isDebugEnabled()) LOG.debug("Enabling NAT Service at {}", baseUrl);
        
        synchronized (getMutex()) {
            EdgeGateway edgeGateway = getEdgeGateway();
            GatewayFeaturesType gatewayFeatures = getGatewayFeatures(edgeGateway);
            NatServiceType natService = tryFindService(gatewayFeatures.getNetworkService(), NatServiceType.class).get();
    
            // Modify
            natService.setIsEnabled(true);
            
            // Execute task
            Task task = edgeGateway.configureServices(gatewayFeatures);
            waitForTask(task, "enable nat-service");
        }
    }

    protected Object getMutex() {
        return mutex;
    }
    
    protected void waitForTask(Task task, String summary) throws VCloudException {
        checkNotNull(task, "task null for %s", summary);
        try {
            task.waitForTask(0);
        } catch (TimeoutException e) {
            throw Exceptions.propagate(e);
        }
    }

    private void checkPublicIps(Iterable<String> publicIps, List<SubnetParticipationType> subnetParticipations) {
        for (String publicIp : publicIps) {
            checkPublicIp(publicIp, subnetParticipations);
        }
    }
    
    private void checkPublicIp(final String publicIp, List<SubnetParticipationType> subnetParticipations) {
        boolean found = false;
        for (SubnetParticipationType subnetParticipation : subnetParticipations) {
            Iterator<IpRangeType> iter = subnetParticipation.getIpRanges().getIpRange().iterator();
            while (!found && iter.hasNext()) {
                IpRangeType ipRangeType = iter.next();
                long ipLo = ipToLong(InetAddresses.forString(ipRangeType.getStartAddress()));
                long ipHi = ipToLong(InetAddresses.forString(ipRangeType.getEndAddress()));
                long ipToTest = ipToLong(InetAddresses.forString(publicIp));
                found = ipToTest >= ipLo && ipToTest <= ipHi;
            }
        }
        if (!found) {
            StringBuilder builder = new StringBuilder();
            builder.append("PublicIp '" + publicIp + "' is not valid. Public IP must fall in the following ranges: ");
            for (SubnetParticipationType subnetParticipation : subnetParticipations) {
                for (IpRangeType ipRangeType : subnetParticipation.getIpRanges().getIpRange()) {
                    builder.append(ipRangeType.getStartAddress());
                    builder.append(" - ");
                    builder.append(ipRangeType.getEndAddress());
                    builder.append(", ");
                }
            }
            LOG.error(builder.toString()+" (rethrowing)");
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

    protected EdgeGateway getEdgeGateway() throws VCloudException {
        List<ReferenceType> edgeGatewayRef = queryEdgeGateways();
        return EdgeGateway.getEdgeGatewayById(client, edgeGatewayRef.get(0).getId());
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
        return Maybe.absent("No service of type "+type.getSimpleName());
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
            
            // Client login
            VcloudClient vcloudClient = null;
            boolean versionFound = false;
            for (Version version : VCLOUD_VERSIONS) {
                try {
                    vcloudClient = new VcloudClient(endpoint, version);
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
            if (trustStore != null) {
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
        gatewayNatRule.setOriginalPort(""+original.getPort());
        gatewayNatRule.setTranslatedIp(translated.getHostText());
        gatewayNatRule.setTranslatedPort(""+translated.getPort());
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
