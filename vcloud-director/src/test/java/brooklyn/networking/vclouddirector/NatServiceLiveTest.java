package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertNotNull;

import java.util.List;

import org.testng.annotations.Test;

import brooklyn.location.PortRange;
import brooklyn.location.basic.PortRanges;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.util.net.Protocol;

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
        List<NatRuleType> rules = service.getNatRules();
        assertNotNull(rules);
    }

    // TAI2.0 (as at 2015-04-27) is running vcloud-director version 5.5
    @Test(groups="Live")
    public void testGetNatRulesAtTai2() throws Exception {
        JcloudsLocation loc2 = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_TAI_2_SPEC);
        NatService service = newServiceBuilder(loc2)
                .portRange(DEFAULT_PORT_RANGE)
                .build();
        List<NatRuleType> rules = service.getNatRules();
        assertNotNull(rules);
    }

    // The second vDC within a vOrg.
    // TAI2.0 (as at 2015-04-27) is running vcloud-director version 5.5
    @Test(groups="Live")
    public void testGetNatRulesAtTai2b() throws Exception {
        JcloudsLocation loc2 = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_TAI_2b_SPEC);
        NatService service = newServiceBuilder(loc2)
                .portRange(DEFAULT_PORT_RANGE)
                .build();
        List<NatRuleType> rules = service.getNatRules();
        assertNotNull(rules);
    }
    
    // Simple test that just checks no errors (e.g. can authenticate etc)
    @Test(groups="Live")
    public void testGetNatRules() throws Exception {
        NatService service = newServiceBuilder(loc).build();
        List<NatRuleType> rules = service.getNatRules();
        assertNotNull(rules);
    }
    
    @Test(groups="Live")
    public void testAddNatRuleAtTai2() throws Exception {
        JcloudsLocation loc2 = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_TAI_2_SPEC);
        String publicIp2 = (String) checkNotNull(loc2.getAllConfigBag().getStringKey("advancednetworking.vcloud.network.publicip"), "publicip");

        HostAndPort targetEndpoint = HostAndPort.fromParts(INTERNAL_MACHINE_IP_TAI_2, 1);
        HostAndPort publicEndpoint = HostAndPort.fromString(publicIp2);
        PortRange publicPortRange = PortRanges.fromString(STARTING_PORT+"-"+(STARTING_PORT+10));
        HostAndPort actualEndpoint = null;
        try {
            actualEndpoint = openPortForwarding(loc2, new PortForwardingConfig()
                    .protocol(Protocol.TCP)
                    .publicEndpoint(publicEndpoint)
                    .publicPortRange(publicPortRange)
                    .targetEndpoint(targetEndpoint));
            
            assertRuleExists(actualEndpoint, targetEndpoint);
            
        } finally {
            if (actualEndpoint != null) {
                closePortForwarding(loc2, new PortForwardingConfig()
                        .protocol(Protocol.TCP)
                        .publicEndpoint(actualEndpoint)
                        .targetEndpoint(targetEndpoint));
                assertRuleNotExists(actualEndpoint, targetEndpoint, Protocol.TCP);
            }
        }
    }

    @Test(groups="Live")
    public void testAddNatRuleAtTai2b() throws Exception {
        JcloudsLocation loc2 = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_TAI_2b_SPEC);
        String publicIp2 = (String) checkNotNull(loc2.getAllConfigBag().getStringKey("advancednetworking.vcloud.network.publicip"), "publicip");

        HostAndPort targetEndpoint = HostAndPort.fromParts(INTERNAL_MACHINE_IP_TAI_2b, 1);
        HostAndPort publicEndpoint = HostAndPort.fromString(publicIp2);
        PortRange publicPortRange = PortRanges.fromString(STARTING_PORT+"-"+(STARTING_PORT+10));
        HostAndPort actualEndpoint = null;
        try {
            actualEndpoint = openPortForwarding(loc2, new PortForwardingConfig()
                    .protocol(Protocol.TCP)
                    .publicEndpoint(publicEndpoint)
                    .publicPortRange(publicPortRange)
                    .targetEndpoint(targetEndpoint));
            
            assertRuleExists(actualEndpoint, targetEndpoint);
            
        } finally {
            if (actualEndpoint != null) {
                closePortForwarding(loc2, new PortForwardingConfig()
                        .protocol(Protocol.TCP)
                        .publicEndpoint(actualEndpoint)
                        .targetEndpoint(targetEndpoint));
                assertRuleNotExists(actualEndpoint, targetEndpoint, Protocol.TCP);
            }
        }
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
        return openPortForwarding(loc, config);
    }

    protected HostAndPort closePortForwarding(PortForwardingConfig config) throws Exception {
        return closePortForwarding(loc, config);
    }
    
    protected HostAndPort openPortForwarding(JcloudsLocation loc, PortForwardingConfig config) throws Exception {
        Object mutex = (sharedMutex == null) ? new Object() : sharedMutex;
        NatService service = newServiceBuilder(loc).mutex(mutex).build();
        return service.openPortForwarding(config);
    }
    
    protected HostAndPort closePortForwarding(JcloudsLocation loc, PortForwardingConfig config) throws Exception {
        Object mutex = (sharedMutex == null) ? new Object() : sharedMutex;
        NatService service = newServiceBuilder(loc).mutex(mutex).build();
        return service.closePortForwarding(config);
    }
}
