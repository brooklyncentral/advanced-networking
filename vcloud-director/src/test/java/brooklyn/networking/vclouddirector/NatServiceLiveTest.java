package brooklyn.networking.vclouddirector;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.util.List;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.BrooklynAppLiveTestSupport;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.networking.vclouddirector.NatService.OpenPortForwardingConfig;
import brooklyn.util.net.Protocol;

import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;
import com.vmware.vcloud.api.rest.schema.NatRuleType;

public class NatServiceLiveTest extends BrooklynAppLiveTestSupport {

    private static final String LOCATION_SPEC = "canopy-vCHS";

    private static final String LOCATION_TAI_SPEC = "canopy-TAI";

    public static final String INTERNAL_MACHINE_IP = "192.168.109.10";
    
    public static final String NETWORK_ID = "041e176a-befc-4b28-89e2-3c5343ff4d12";
    public static final String PUBLIC_IP = "23.92.230.21";

    protected JcloudsLocation loc;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_SPEC);
    }
    
    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }
    
    // TAI is (currently as at 2014-12-16) running vcloud-director version 5.1
    @Test(groups="Live")
    public void testGetNatRulesAtTai() throws Exception {
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_TAI_SPEC);
        NatService service = NatService.builder().location(loc).build();
        List<NatRuleType> rules = service.getNatRules(service.getEdgeGateway());
        assertNotNull(rules);
    }
    
    // Simple test that just checks no errors (e.g. can authenticate etc)
    @Test(groups="Live")
    public void testGetNatRules() throws Exception {
        NatService service = NatService.builder().location(loc).build();
        List<NatRuleType> rules = service.getNatRules(service.getEdgeGateway());
        assertNotNull(rules);
    }
    
    @Test(groups="Live")
    public void testAddNatRule() throws Exception {
        NatService service = NatService.builder().location(loc).build();
        HostAndPort result = service.openPortForwarding(new OpenPortForwardingConfig()
                .networkId(NETWORK_ID)
                .publicIp(PUBLIC_IP)
                .protocol(Protocol.TCP)
                .target(HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1235))
                .publicPort(5679));
        try {
            assertEquals(result, HostAndPort.fromParts(PUBLIC_IP, 5679));
    
            // Confirm the rule exists
            NatService service2 = NatService.builder().location(loc).build();
            List<NatRuleType> rules = service.getNatRules(service2.getEdgeGateway());
            NatRuleType rule = Iterables.find(rules, NatPredicates.translatedTargetEquals(INTERNAL_MACHINE_IP, 1235));
            assertEquals(rule.getGatewayNatRule().getOriginalIp(), PUBLIC_IP);
            assertEquals(rule.getGatewayNatRule().getOriginalPort(), "5679");
            assertEquals(rule.getGatewayNatRule().getTranslatedIp(), INTERNAL_MACHINE_IP);
            assertEquals(rule.getGatewayNatRule().getTranslatedPort(), "1235");
        } finally {
            // TODO Delete NAT rule
        }
    }
}
