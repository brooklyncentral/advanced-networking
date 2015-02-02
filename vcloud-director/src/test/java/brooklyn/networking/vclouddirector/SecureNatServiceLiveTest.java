package brooklyn.networking.vclouddirector;

import static org.testng.Assert.assertNotNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.Executors;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.vmware.vcloud.api.rest.schema.NatRuleType;

import brooklyn.entity.BrooklynAppLiveTestSupport;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.util.exceptions.Exceptions;

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
 * brooklyn.location.named.canopy-vCHS.trustStore=/Library/Java/JavaVirtualMachines/jdk1.7.0_71.jdk/Contents/Home/jre/lib/security/cacerts
 * brooklyn.location.named.canopy-vCHS.trustStorePassword=changeit
 *
 * brooklyn.location.named.canopy-TAI=jclouds:vcloud-director:https://svdc.it-solutions.atos.net/api
 * brooklyn.location.named.canopy-TAI.identity=jo.blogs@myvorg_01
 * brooklyn.location.named.canopy-TAI.credential=pa55w0rd
 * brooklyn.location.named.canopy-TAI.trustStore=/Library/Java/JavaVirtualMachines/jdk1.7.0_71.jdk/Contents/Home/jre/lib/security/cacerts
 * brooklyn.location.named.canopy-TAI.trustStorePassword=changeit
 * </pre> 
 */
public class SecureNatServiceLiveTest extends BrooklynAppLiveTestSupport {

    // 
    private static final String LOCATION_SPEC = "canopy-vCHS";

    private static final String LOCATION_TAI_SPEC = "canopy-TAI";

    protected JcloudsLocation loc;

    protected ListeningExecutorService executor;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_SPEC);

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
    
    // TAI (as at 2014-12-16) is running vcloud-director version 5.1
    @Test(groups="Live")
    public void testGetNatRulesAtTai() throws Exception {
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_TAI_SPEC);
        NatService service = newServiceBuilder(loc).build();
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

    private NatService.Builder newServiceBuilder(JcloudsLocation loc) {
        String endpoint = loc.getEndpoint();

        // jclouds endpoint has suffix "/api"; but VMware SDK wants it without "api"
        String convertedUri;
        try {
            URI uri = URI.create(endpoint);
            convertedUri = new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (URISyntaxException e) {
            throw Exceptions.propagate(e);
        }

        String trustStore = (String) loc.getAllConfigBag().getStringKey("trustStore");
        String trustStorePassword = (String) loc.getAllConfigBag().getStringKey("trustStorePassword");

        return NatService.builder()
                .identity(loc.getIdentity())
                .credential(loc.getCredential())
                .endpoint(convertedUri)
                .trustStore(trustStore)
                .trustStorePassword(trustStorePassword);
    }
}
