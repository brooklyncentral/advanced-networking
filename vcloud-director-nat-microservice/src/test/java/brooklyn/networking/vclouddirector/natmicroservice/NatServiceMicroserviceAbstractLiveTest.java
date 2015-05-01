package brooklyn.networking.vclouddirector.natmicroservice;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.escape.Escaper;
import com.google.common.net.HostAndPort;
import com.google.common.net.UrlEscapers;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.GenericType;

import brooklyn.config.BrooklynProperties;
import brooklyn.entity.basic.Entities;
import brooklyn.location.PortRange;
import brooklyn.location.basic.PortRanges;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.management.internal.LocalManagementContext;
import brooklyn.networking.vclouddirector.NatServiceDispatcher;
import brooklyn.networking.vclouddirector.NatServiceDispatcher.EndpointConfig;
import brooklyn.networking.vclouddirector.PortForwardingConfig;
import brooklyn.networking.vclouddirector.natservice.domain.NatRuleSummary;
import brooklyn.test.entity.LocalManagementContextForTests;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.guava.Maybe;
import brooklyn.util.net.Protocol;


public abstract class NatServiceMicroserviceAbstractLiveTest extends AbstractRestApiTest {

    private static final Logger LOG = LoggerFactory.getLogger(NatServiceMicroserviceAbstractLiveTest.class);

    private static final int STARTING_PORT = 19980;
    private static final PortRange DEFAULT_PORT_RANGE = PortRanges.fromString("19980-19999");
    
    private static final String INTERNAL_MACHINE_IP = "192.168.109.10";

    private LocalManagementContext mgmt;
    private JcloudsLocation loc;
    private String endpoint;
    private String identity;
    private String credential;
    private String publicIp;
    private Random random = new Random(getClass().getName().hashCode());

    private Escaper escaper = UrlEscapers.urlPathSegmentEscaper();
    
    protected abstract String getLocationSpec();

    @BeforeClass(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        mgmt = new LocalManagementContextForTests(BrooklynProperties.Factory.newDefault());
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(getLocationSpec());
        endpoint = endpoint(loc);
        identity = loc.getIdentity();
        credential = loc.getCredential();
        publicIp = (String) checkNotNull(loc.getAllConfigBag().getStringKey("advancednetworking.vcloud.network.publicip"), "publicip");

        super.setUp();
    }

    @AfterClass(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        if (mgmt != null) Entities.destroyAll(mgmt);
        super.tearDown();
    }

    protected NatServiceDispatcher newNatServiceDispatcher() {
        return NatServiceDispatcher.builder()
                .endpoint(endpoint, new EndpointConfig(null, null, null))
                .portRange(DEFAULT_PORT_RANGE)
                .build();
    }
    
    @Test(groups="Live")
    public void testGetNatRules() throws Exception {
        JcloudsLocation loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(getLocationSpec());
        String url = "/v1/nat"
                + "?endpoint="+escaper.escape(endpoint)
                + "&identity="+escaper.escape(identity)
                + "&credential="+escaper.escape(credential);
        List<NatRuleSummary> data = client().resource(url).get(new GenericType<List<NatRuleSummary>>() {});
        LOG.info("/v1/nat gives: "+data);
        
        // Expect at least one rule
        assertFalse(data.isEmpty());
    }

    @Test(groups="Live")
    public void testOpenAndDeleteNatRule() throws Exception {
        runOpenAndDeleteNatRule(null, null);
    }

    @Test(groups="Live")
    public void testOpenAndDeleteNatRuleWithExplicitPublicPort() throws Exception {
        runOpenAndDeleteNatRule(STARTING_PORT+10, null);
    }
    
    @Test(groups="Live")
    public void testOpenAndDeleteNatRuleWithExplicitPublicPortRange() throws Throwable {
        runOpenAndDeleteNatRule(null, PortRanges.fromString((STARTING_PORT+5)+"-"+(STARTING_PORT+10)));
    }
    
    protected void runOpenAndDeleteNatRule(Integer publicPort, PortRange publicPortRange) throws Exception {
        HostAndPort publicEndpoint = (publicPort == null)
                ? HostAndPort.fromString(publicIp) 
                : HostAndPort.fromParts(publicIp, publicPort);
        HostAndPort targetEndpoint = HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1+random.nextInt(1000));
        String protocol = "tcp";

        // Open the NAT rule
        String openUrl = "/v1/nat"
                + "?endpoint="+escaper.escape(endpoint)
                + "&identity="+escaper.escape(identity)
                + "&credential="+escaper.escape(credential)
                + "&protocol=" + protocol
                + "&original=" + publicEndpoint.toString()
                + (publicPortRange == null ? "" : "&originalPortRange="+publicPortRange.toString())
                + "&translated=" + targetEndpoint.toString();
        ClientResponse openResponse = client().resource(openUrl).put(ClientResponse.class);
        HostAndPort result = HostAndPort.fromString(openResponse.getEntity(String.class));
        try {
            assertEquals(openResponse.getStatus(), 200);
            if (publicPort != null) {
                assertEquals(result, publicEndpoint);
            } else {
                assertEquals(result.getHostText(), publicIp, "result="+result);
                assertTrue(result.hasPort(), "result="+result);
                if (publicPortRange != null) {
                    assertTrue(contains(publicPortRange, result.getPort()), "result="+result+"; range="+publicPortRange);
                } else {
                    assertTrue(contains(DEFAULT_PORT_RANGE, result.getPort()), "result="+result+"; range="+DEFAULT_PORT_RANGE);
                }
            }
            
            LOG.info("NAT Rule created: "+result);
            assertRuleExists(result, targetEndpoint, protocol);
            
            // Delete the rule
            String deleteUrl = "/v1/nat"
                    + "?endpoint="+escaper.escape(endpoint)
                    + "&identity="+escaper.escape(identity)
                    + "&credential="+escaper.escape(credential)
                    + "&protocol=" + protocol
                    + "&original=" + result.toString()
                    + "&translated=" + targetEndpoint.toString();
            ClientResponse deleteResponse = client().resource(deleteUrl).delete(ClientResponse.class);
            assertEquals(deleteResponse.getStatus(), 200);
            
            // Confirm rule no longer there
            LOG.info("NAT Rule deleted: "+result);
            assertRuleNotExists(result, targetEndpoint, protocol);
        } finally {
            if (result != null) {
                dispatcher.closePortForwarding(endpoint, identity, credential, new PortForwardingConfig()
                        .protocol(Protocol.TCP)
                        .publicEndpoint(result)
                        .targetEndpoint(targetEndpoint));
            }
        }
    }
    
    protected void assertRuleExists(HostAndPort publicEndpoint, HostAndPort targetEndpoint, String protocol) throws Exception {
        Maybe<NatRuleSummary> rule = tryFindRule(publicEndpoint, targetEndpoint, protocol);
        assertNotNull(rule.get(), "rule="+rule);
    }

    protected void assertRuleNotExists(HostAndPort publicEndpoint, HostAndPort targetEndpoint, String protocol) throws Exception {
        Maybe<NatRuleSummary> rule = tryFindRule(publicEndpoint, targetEndpoint, protocol);
        assertTrue(rule.isAbsent(), "rule="+rule);
    }

    protected Maybe<NatRuleSummary> tryFindRule(HostAndPort publicEndpoint, HostAndPort targetEndpoint, String protocol) throws Exception {
        // Confirm the NAT rule exists
        String getUrl = "/v1/nat"
                + "?endpoint="+escaper.escape(endpoint)
                + "&identity="+escaper.escape(identity)
                + "&credential="+escaper.escape(credential);
        List<NatRuleSummary> rules = client().resource(getUrl).get(new GenericType<List<NatRuleSummary>>() {});
        for (NatRuleSummary rule : rules) {
            if (publicEndpoint.getHostText().equals(rule.getOriginalIp()) 
                    && Integer.toString(publicEndpoint.getPort()).equals(rule.getOriginalPort())
                    && targetEndpoint.getHostText().equals(rule.getTranslatedIp()) 
                    && Integer.toString(targetEndpoint.getPort()).equals(rule.getTranslatedPort())
                    && protocol.equalsIgnoreCase(rule.getProtocol())) {
                return Maybe.of(rule);
            }
        }
        return Maybe.absent("No rule for "+publicEndpoint+"->"+targetEndpoint);
    }

    protected boolean contains(PortRange range, int port) {
        for (int contender : range) {
            if (contender == port) return true;
        }
        return false;
    }
    
    protected String endpoint(JcloudsLocation loc) {
        // jclouds endpoint has suffix "/api"; but VMware SDK wants it without "api"
        try {
            URI uri = URI.create(loc.getEndpoint());
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (URISyntaxException e) {
            throw Exceptions.propagate(e);
        }
    }
}
