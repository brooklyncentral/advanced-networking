package brooklyn.networking.vclouddirector.natmicroservice;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.GenericType;

import brooklyn.config.BrooklynProperties;
import brooklyn.entity.basic.Entities;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.management.ManagementContext;
import brooklyn.networking.vclouddirector.NatServiceDispatcher;
import brooklyn.networking.vclouddirector.NatServiceDispatcher.TrustConfig;
import brooklyn.networking.vclouddirector.natservice.domain.NatRuleSummary;
import brooklyn.test.entity.LocalManagementContextForTests;
import brooklyn.util.exceptions.Exceptions;

public class NatServiceMicroserviceLiveTest extends AbstractRestApiTest {

    private static final Logger LOG = LoggerFactory.getLogger(NatServiceMicroserviceLiveTest.class);

    private ManagementContext mgmt;
    private JcloudsLocation loc;

    private String trustStore;
    private String trustStorePassword;
    
    @BeforeClass(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        mgmt = new LocalManagementContextForTests(BrooklynProperties.Factory.newDefault());
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve("canopy-vCHS");
        trustStore = (String) loc.getAllConfigBag().getStringKey("trustStore");
        trustStorePassword = (String) loc.getAllConfigBag().getStringKey("trustStorePassword");

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
                .endpoint(endpoint(loc), new TrustConfig(trustStore, trustStorePassword))
                .build();
    }
    
    @Test(groups="Live")
    public void testGetNatRules() throws Exception {
        Escaper escaper = UrlEscapers.urlPathSegmentEscaper();
        JcloudsLocation loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve("canopy-vCHS");
        String url = "/v1/nat"
                + "?endpoint="+escaper.escape(endpoint(loc))
                + "&identity="+escaper.escape(loc.getIdentity())
                + "&credential="+escaper.escape(loc.getCredential());
        List<NatRuleSummary> data = client().resource(url).get(new GenericType<List<NatRuleSummary>>() {});
        LOG.info("/v1/nat gives: "+data);
        
        // Expect at least one rule
        assertFalse(data.isEmpty());
    }
    
    @Test(groups="Live")
    public void testOpenAndDeleteNatRule() throws Exception {
        Escaper escaper = UrlEscapers.urlPathSegmentEscaper();
        JcloudsLocation loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve("canopy-vCHS");
        
        String originalIp = (String) loc.getAllConfigBag().getStringKey("advancednetworking.vcloud.network.publicip");
        int originalPort = 45678;
        String translatedIp = "192.168.109.10";
        int translatedPort = 1234;
        String protocol = "tcp";
        
        // Open the NAT rule
        String openUrl = "/v1/nat"
                + "?endpoint="+escaper.escape(endpoint(loc))
                + "&identity="+escaper.escape(loc.getIdentity())
                + "&credential="+escaper.escape(loc.getCredential())
                + "&protocol=" + protocol
                + "&original=" + originalIp + ":" + originalPort
                + "&translated=" + translatedIp + ":" + translatedPort;
        ClientResponse openResponse = client().resource(openUrl).put(ClientResponse.class);
        assertEquals(openResponse.getStatus(), 200);

        // Confirm the NAT rule exists
        String getUrl = "/v1/nat"
                + "?endpoint="+escaper.escape(endpoint(loc))
                + "&identity="+escaper.escape(loc.getIdentity())
                + "&credential="+escaper.escape(loc.getCredential());
        List<NatRuleSummary> rules = client().resource(getUrl).get(new GenericType<List<NatRuleSummary>>() {});
        NatRuleSummary found = null;
        for (NatRuleSummary rule : rules) {
            if (originalIp.equals(rule.getOriginalIp()) && Integer.toString(originalPort).equals(rule.getOriginalPort())
                    && translatedIp.equals(rule.getTranslatedIp()) && Integer.toString(translatedPort).equals(rule.getTranslatedPort())
                    && protocol.equals(rule.getProtocol())) {
                found = rule;
                break;
            }
        }
        assertNotNull(found, "rules="+rules);
        
        // Delete the rule
        String deleteUrl = "/v1/nat"
                + "?endpoint="+escaper.escape(endpoint(loc))
                + "&identity="+escaper.escape(loc.getIdentity())
                + "&credential="+escaper.escape(loc.getCredential())
                + "&protocol=" + protocol
                + "&original=" + originalIp + ":" + originalPort
                + "&translated=" + translatedIp + ":" + translatedPort;
        ClientResponse deleteResponse = client().resource(deleteUrl).delete(ClientResponse.class);
        assertEquals(deleteResponse.getStatus(), 200);
        
        // Confirm rule no longer there
        rules = client().resource(getUrl).get(new GenericType<List<NatRuleSummary>>() {});
        found = null;
        for (NatRuleSummary rule : rules) {
            if (originalIp.equals(rule.getOriginalIp()) && Integer.toString(originalPort).equals(rule.getOriginalPort())
                    && translatedIp.equals(rule.getTranslatedIp()) && Integer.toString(translatedPort).equals(rule.getTranslatedPort())
                    && protocol.equals(rule.getProtocol())) {
                found = rule;
                break;
            }
        }
        assertNull(found, "rules="+rules);
    }
    
    private String endpoint(JcloudsLocation loc) {
        // jclouds endpoint has suffix "/api"; but VMware SDK wants it without "api"
        try {
            URI uri = URI.create(loc.getEndpoint());
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (URISyntaxException e) {
            throw Exceptions.propagate(e);
        }
    }
}
