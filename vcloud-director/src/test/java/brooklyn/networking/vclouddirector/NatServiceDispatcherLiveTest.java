package brooklyn.networking.vclouddirector;

import java.net.URI;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.net.HostAndPort;

import brooklyn.networking.vclouddirector.NatServiceDispatcher.EndpointConfig;

/**
 * Tests assume that brooklyn.properties have been configured with location specs for vCHS.
 * For example:
 * 
 * <pre>
 * brooklyn.location.named.canopy-vCHS=jclouds:vcloud-director:https://p5v1-vcd.vchs.vmware.com/api
 * brooklyn.location.named.canopy-vCHS.identity=jo.blogs@cloudsoftcorp.com@M123456789-1234
 * brooklyn.location.named.canopy-vCHS.credential=pa55w0rd
 * brooklyn.location.named.canopy-vCHS.advancednetworking.vcloud.network.id=041e176a-befc-4b28-89e2-3c5343ff4d12
 * brooklyn.location.named.canopy-vCHS.advancednetworking.vcloud.network.publicip=23.92.230.21
 * </pre> 
 */
@Test(groups="Live")
public class NatServiceDispatcherLiveTest extends AbstractNatServiceLiveTest {

    private String endpoint;
    private String vDC;
    private String identity;
    private String credential;
    
    private NatServiceDispatcher dispatcher;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        URI uri = URI.create(loc.getEndpoint());
        endpoint = new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null).toString();
        vDC = loc.getRegion();
        identity = loc.getIdentity();
        credential = loc.getCredential();
        
        dispatcher = NatServiceDispatcher.builder()
                .endpoint(endpoint, new EndpointConfig(null, null, null))
                .portRange(DEFAULT_PORT_RANGE)
                .build();
    }
    
    protected HostAndPort openPortForwarding(PortForwardingConfig config) throws Exception {
        return dispatcher.openPortForwarding(endpoint, vDC, identity, credential, config);
    }
    
    protected HostAndPort closePortForwarding(PortForwardingConfig config) throws Exception {
        return dispatcher.closePortForwarding(endpoint, vDC, identity, credential, config);
    }
}
