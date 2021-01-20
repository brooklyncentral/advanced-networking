package brooklyn.networking.vclouddirector.nat;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.net.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.vmware.vcloud.api.rest.schema.NatPortForwardingRuleType;
import com.vmware.vcloud.api.rest.schema.NatRuleType;
import com.vmware.vcloud.api.rest.schema.NatVmRuleType;

/**
 * Tests assume that brooklyn.properties have been configured with location specs for vCHS and TAI.
 * For example:
 * 
 * <pre>
 * brooklyn.location.named.canopy-vCHS=jclouds:vcloud-director:https://p5v1-vcd.vchs.vmware.com/api
 * brooklyn.location.named.canopy-vCHS.identity=jo.blogs@cloudsoftcorp.com@M123456789-1234
 * brooklyn.location.named.canopy-vCHS.credential=pa55w0rd
 * brooklyn.location.named.canopy-vCHS.advancednetworking.vcloud.network.id=041e176a-befc-4b28-89e2-3c5343ff4d12
 * brooklyn.location.named.canopy-vCHS.advancednetworking.vcloud.network.publicip=23.92.230.21
 *
 * brooklyn.location.named.canopy-TAI=jclouds:vcloud-director:https://svdc.it-solutions.atos.net/api
 * brooklyn.location.named.canopy-TAI.identity=jo.blogs@myvorg_01
 * brooklyn.location.named.canopy-TAI.credential=pa55w0rd
 * </pre> 
 */
public abstract class AbstractNatServiceLiveTest extends BrooklynAppLiveTestSupport {

    // TODO Want test for concurrent interleaved open and close calls (similar idea to 
    // testAddNatRulesConcurrently).
    
    private static final Logger LOG = LoggerFactory.getLogger(AbstractNatServiceLiveTest.class);

    public static final String LOCATION_VCHS = "canopy-vCHS";

    public static final String LOCATION_TAI_SPEC = "Canopy_TAI_TEST"; //"canopy-TAI";

    public static final String LOCATION_TAI_2_SPEC = "Canopy_TAI_2";

    // A second vDC in a vOrg within TAI 2.0
    public static final String LOCATION_TAI_2b_SPEC = "Canopy_TAI_2b";

    public static final String LOCATION_SPEC = LOCATION_TAI_SPEC;

    public static final String INTERNAL_MACHINE_IP = "192.168.109.10";

    public static final String INTERNAL_MACHINE_IP_TAI_2 = "192.168.10.110"; // IP within TAI 2.0's first vDC

    public static final String INTERNAL_MACHINE_IP_TAI_2b = "192.168.11.110"; // IP within TAI 2.0's second vDC

    public static final int STARTING_PORT = 19980;
    public static final PortRange DEFAULT_PORT_RANGE = PortRanges.fromString("19980-19999");
    
    protected JcloudsLocation loc;
    protected String publicIp;
    protected ListeningExecutorService executor;

    // Generates a random port number, but in a deterministic way such that each test will (most likely) 
    // use its own port (the port used is dependent on order that tests are run in).
    protected Random random = new Random(getClass().getName().hashCode());
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        loc = (JcloudsLocation) mgmt.getLocationRegistry().getLocationManaged(LOCATION_SPEC);
        publicIp = (String) checkNotNull(loc.config().getBag().getStringKey("advancednetworking.vcloud.network.publicip"), "publicip");
        
        executor = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
    }
    
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        try {
            super.tearDown();
        } finally {
            executor.shutdownNow();
        }
    }
    
    @Test(groups="Live")
    public void testAddNatRule() throws Throwable {
        runAddNatRule(null, null);
    }
    
    @Test(groups="Live")
    public void testAddNatRuleWithExplicitPublicPort() throws Throwable {
        runAddNatRule(STARTING_PORT+10, null);
    }
    
    @Test(groups="Live")
    public void testAddNatRuleWithExplicitPublicPortRange() throws Throwable {
        runAddNatRule(null, PortRanges.fromString((STARTING_PORT+5)+"-"+(STARTING_PORT+10)));
    }
    
    protected void runAddNatRule(Integer publicPort, PortRange publicPortRange) throws Throwable {
        HostAndPort publicEndpoint = (publicPort == null)
                ? HostAndPort.fromString(publicIp) 
                : HostAndPort.fromParts(publicIp, publicPort);
        HostAndPort targetEndpoint = HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1+random.nextInt(1000));
        
        HostAndPort result = openPortForwarding(new PortForwardingConfig()
                .protocol(Protocol.TCP)
                .publicEndpoint(publicEndpoint)
                .publicPortRange(publicPortRange)
                .targetEndpoint(targetEndpoint));
        
        Throwable throwable = null;
        try {
            if (publicPort != null) {
                assertEquals(result, publicEndpoint);
            } else {
                assertEquals(result.getHost(), publicIp, "result="+result);
                assertTrue(result.hasPort(), "result="+result);
                if (publicPortRange != null) {
                    assertTrue(contains(publicPortRange, result.getPort()), "result="+result+"; range="+publicPortRange);
                } else {
                    assertTrue(contains(DEFAULT_PORT_RANGE, result.getPort()), "result="+result+"; range="+DEFAULT_PORT_RANGE);
                }
            }
            assertRuleExists(result, targetEndpoint);

        } catch (Throwable t) {
            throwable = t;
        } finally {
            try {
                closePortForwarding(new PortForwardingConfig()
                        .protocol(Protocol.TCP)
                        .publicEndpoint(result)
                        .targetEndpoint(targetEndpoint));
                assertRuleNotExists(result, targetEndpoint, Protocol.TCP);
            } catch (Exception e) {
                if (throwable == null) {
                    throwable = e;
                } else {
                    LOG.error("Problem closing port-forwarding, but will propagate original problem from opening", e);
                }
            }
        }
        if (throwable != null) throw throwable;
    }
    
    @Test(groups="Live")
    public void testAddNatRulesConcurrently() throws Exception {
        final int NUM_PORTS = 3;
        final List<ListenableFuture<HostAndPort>> futures = Lists.newArrayList();
        
        for (int i = 0; i < 3; i++) {
            final HostAndPort targetEndpoint = HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1+i);
            final HostAndPort publicEndpoint = HostAndPort.fromString(publicIp);
            final PortRange publicPortRange = PortRanges.fromString(STARTING_PORT+"-"+(STARTING_PORT+10));
            
            ListenableFuture<HostAndPort> future = executor.submit(new Callable<HostAndPort>() {
                public HostAndPort call() throws Exception {
                    return openPortForwarding(new PortForwardingConfig()
                            .protocol(Protocol.TCP)
                            .publicEndpoint(publicEndpoint)
                            .publicPortRange(publicPortRange)
                            .targetEndpoint(targetEndpoint));
                }});
            futures.add(future);
        }
        
        try {
            // Wait for all to complete, even if some fail; and then assert that they all succeeded
            List<HostAndPort> results = Futures.successfulAsList(futures).get();
            Futures.allAsList(futures).get();
            
            // Confirm the rules exist
            for (int i = 0; i < NUM_PORTS; i++) {
                HostAndPort targetEndpoint = HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1+i);
                HostAndPort publicEndpoint = results.get(i);
                assertRuleExists(publicEndpoint, targetEndpoint);
            }
            
        } finally {
            List<HostAndPort> results = Futures.successfulAsList(futures).get();

            for (int i = 0; i < NUM_PORTS; i++) {
                final HostAndPort targetEndpoint = HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1+i);
                final HostAndPort publicEndpoint = results.get(i);
                if (publicEndpoint != null) {
                    ListenableFuture<HostAndPort> future = executor.submit(new Callable<HostAndPort>() {
                        public HostAndPort call() throws Exception {
                            HostAndPort result = closePortForwarding(new PortForwardingConfig()
                                    .protocol(Protocol.TCP)
                                    .publicEndpoint(publicEndpoint)
                                    .targetEndpoint(targetEndpoint));
                            assertRuleNotExists(publicEndpoint, targetEndpoint, Protocol.TCP);
                            return result;
                        }});
                    futures.add(future);
                }
            }
            
            // Wait for all close-tasks to complete, even if some fail; and then assert that they all succeeded
            Futures.successfulAsList(futures).get();
            Futures.allAsList(futures).get();
        }
    }

    protected abstract HostAndPort openPortForwarding(PortForwardingConfig config) throws Exception;
    
    protected abstract HostAndPort closePortForwarding(PortForwardingConfig config) throws Exception;
    
    protected void assertRuleExists(HostAndPort expectedPublicEndpoint, HostAndPort targetEndpoint) throws Exception {
        NatService service = newServiceBuilder(loc).build();
        List<NatRuleType> rules = service.getNatRules();
        NatRuleType rule = Iterables.tryFind(rules, NatPredicates.translatedEndpointEquals(targetEndpoint)).get();
        
        assertEquals(rule.getGatewayNatRule().getOriginalIp(), expectedPublicEndpoint.getHost());
        assertEquals(rule.getGatewayNatRule().getOriginalPort(), ""+expectedPublicEndpoint.getPort());
        assertEquals(rule.getGatewayNatRule().getTranslatedIp(), targetEndpoint.getHost());
        assertEquals(rule.getGatewayNatRule().getTranslatedPort(), ""+targetEndpoint.getPort());
    }
    
    protected void assertRuleNotExists(HostAndPort publicEndpoint, HostAndPort targetEndpoint, Protocol protocol) throws Exception {
        NatService service = newServiceBuilder(loc).build();
        List<NatRuleType> rules = service.getNatRules();
        Optional<NatRuleType> rule = Iterables.tryFind(rules, NatPredicates.translatedEndpointEquals(targetEndpoint));
        assertFalse(rule.isPresent(), (rule.isPresent() ? toString(rule.get()) : rule.toString()));
    }
    
    protected String toString(NatRuleType rule) {
        if (rule == null) return null;
        return MoreObjects.toStringHelper(rule)
                .add("type", rule.getRuleType())
                .add("portForwardingRule", toString(rule.getPortForwardingRule()))
                .add("portForwardingRule", toString(rule.getVmRule()))
                .toString();
    }
    
    private String toString(NatVmRuleType rule) {
        if (rule == null) return null;
        return MoreObjects.toStringHelper(rule)
                .add("protocol", rule.getProtocol())
                .add("internalPort", rule.getInternalPort())
                .add("vAppScopedVmId", rule.getVAppScopedVmId())
                .add("externalIp", rule.getExternalIpAddress())
                .add("externalPort", rule.getExternalPort())
                .toString();
    }

    protected String toString(NatPortForwardingRuleType rule) {
        if (rule == null) return null;
        return MoreObjects.toStringHelper(rule)
                .add("protocol", rule.getProtocol())
                .add("internalIp", rule.getInternalIpAddress())
                .add("internalPort", rule.getInternalPort())
                .add("externalIp", rule.getExternalIpAddress())
                .add("externalPort", rule.getExternalPort())
                .toString();
    }

    protected boolean contains(PortRange range, int port) {
        for (int contender : range) {
            if (contender == port) return true;
        }
        return false;
    }
    
    protected NatService.Builder newServiceBuilder(JcloudsLocation loc) {
        String endpoint = loc.getEndpoint();

        // jclouds endpoint has suffix "/api"; but VMware SDK wants it without "api"
        String convertedUri;
        try {
            URI uri = URI.create(endpoint);
            convertedUri = new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (URISyntaxException e) {
            throw Exceptions.propagate(e);
        } 

        return NatService.builder()
                .identity(loc.getIdentity())
                .credential(loc.getCredential())
                .portRange(DEFAULT_PORT_RANGE)
                .endpoint(convertedUri);
    }
}
