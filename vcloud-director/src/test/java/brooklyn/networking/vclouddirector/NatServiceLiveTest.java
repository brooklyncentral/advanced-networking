package brooklyn.networking.vclouddirector;

import static org.testng.Assert.assertNotNull;

import java.util.List;

import org.testng.annotations.Test;

import brooklyn.location.jclouds.JcloudsLocation;

import com.google.common.net.HostAndPort;
import com.vmware.vcloud.api.rest.schema.NatRuleType;

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
public class NatServiceLiveTest extends AbstractNatServiceLiveTest {

    // A test can set this, and unset it at the end (in a try-finally block) to influence 
    // the mutex used by openPortForwarding/closePortForwarding.
    private Object sharedMutex = null;
    
    // TAI (as at 2014-12-16) is running vcloud-director version 5.1
    @Test(groups="Live")
    public void testGetNatRulesAtTai() throws Exception {
        JcloudsLocation loc2 = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_TAI_SPEC);
        NatService service = newServiceBuilder(loc2)
                .portRange(DEFAULT_PORT_RANGE)
                .build();
        List<NatRuleType> rules = service.getNatRules(service.getEdgeGateway());
        assertNotNull(rules);
    }
    
    // Simple test that just checks no errors (e.g. can authenticate etc)
    @Test(groups="Live")
    public void testGetNatRules() throws Exception {
        NatService service = newServiceBuilder(loc).build();
        List<NatRuleType> rules = service.getNatRules(service.getEdgeGateway());
        assertNotNull(rules);
    }

    @Test(groups="Live")
    public void testAddNatRulesConcurrently() throws Exception {
        sharedMutex = new Object();
        try {
            super.testAddNatRulesConcurrently();
        } finally {
            sharedMutex = null;
        }
    }
    
    protected HostAndPort openPortForwarding(PortForwardingConfig config) throws Exception {
        Object mutex = (sharedMutex == null) ? new Object() : sharedMutex;
        NatService service = newServiceBuilder(loc).mutex(mutex).build();
        return service.openPortForwarding(config);
    }
    
    protected HostAndPort closePortForwarding(PortForwardingConfig config) throws Exception {
        Object mutex = (sharedMutex == null) ? new Object() : sharedMutex;
        NatService service = newServiceBuilder(loc).mutex(mutex).build();
        return service.closePortForwarding(config);
    }
}
