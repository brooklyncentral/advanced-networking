package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.BrooklynAppLiveTestSupport;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.net.Protocol;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.vmware.vcloud.api.rest.schema.NatRuleType;

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
public class NatServiceDispatcherLiveTest extends BrooklynAppLiveTestSupport {

    // 
    private static final String LOCATION_SPEC = "canopy-vCHS";

    public static final String INTERNAL_MACHINE_IP = "192.168.109.10";
    
    protected JcloudsLocation loc;
    private String publicIp;
    
    protected ListeningExecutorService executor;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        loc = (JcloudsLocation) mgmt.getLocationRegistry().resolve(LOCATION_SPEC);
        publicIp = (String) checkNotNull(loc.getAllConfigBag().getStringKey("advancednetworking.vcloud.network.publicip"), "publicip");
        
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
    public void testAddNatRule() throws Exception {
        
        NatService service = newServiceBuilder(loc).build();
        HostAndPort result = service.openPortForwarding(new PortForwardingConfig()
                .publicIp(publicIp)
                .protocol(Protocol.TCP)
                .target(HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1235))
                .publicPort(5679));
        try {
            assertEquals(result, HostAndPort.fromParts(publicIp, 5679));
    
            // Confirm the rule exists
            NatService service2 = newServiceBuilder(loc).build();
            List<NatRuleType> rules = service2.getNatRules(service2.getEdgeGateway());
            NatRuleType rule = Iterables.find(rules, NatPredicates.translatedTargetEquals(INTERNAL_MACHINE_IP, 1235));
            assertEquals(rule.getGatewayNatRule().getOriginalIp(), publicIp);
            assertEquals(rule.getGatewayNatRule().getOriginalPort(), "5679");
            assertEquals(rule.getGatewayNatRule().getTranslatedIp(), INTERNAL_MACHINE_IP);
            assertEquals(rule.getGatewayNatRule().getTranslatedPort(), "1235");
        } finally {
            service.closePortForwarding(new PortForwardingConfig()
                    .publicIp(publicIp)
                    .protocol(Protocol.TCP)
                    .target(HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1235))
                    .publicPort(5679));
        }
    }

    protected void assertNoRuleForTranslatedTarget(int internalPort) throws Exception {
        NatService service = newServiceBuilder(loc).build();
        List<NatRuleType> rules = service.getNatRules(service.getEdgeGateway());
        Optional<NatRuleType> rule = Iterables.tryFind(rules, NatPredicates.translatedTargetEquals(INTERNAL_MACHINE_IP, internalPort));
        assertFalse(rule.isPresent(), "rule=" + rule);
    }
    
    @Test(groups="Live")
    public void testAddNatRulesConcurrently() throws Exception {
        final Object sharedMutex = new Object();
        final List<ListenableFuture<?>> futures = Lists.newArrayList();
        
        for (int i = 0; i < 3; i++) {
            final int counter = i;
            ListenableFuture<Void> future = executor.submit(new Callable<Void>() {
                public Void call() throws Exception {
                    int internalPort = 1236 + counter;
                    int externalPort = 5680 + counter;
                    NatService service = newServiceBuilder(loc).mutex(sharedMutex).build();
                    HostAndPort result = service.openPortForwarding(new PortForwardingConfig()
                            .publicIp(publicIp)
                            .protocol(Protocol.TCP)
                            .target(HostAndPort.fromParts(INTERNAL_MACHINE_IP, internalPort))
                            .publicPort(externalPort));
                    try {
                        assertEquals(result, HostAndPort.fromParts(publicIp, externalPort));
                    } finally {
                        service.closePortForwarding(new PortForwardingConfig().publicIp(publicIp)
                                .protocol(Protocol.TCP)
                                .target(HostAndPort.fromParts(INTERNAL_MACHINE_IP, 1235))
                                .publicPort(externalPort));
                    }
                    return null;
                }});
            futures.add(future);
        }
        
        Futures.allAsList(futures).get();
        
        // Confirm the rules exist
        NatService service = newServiceBuilder(loc).build();
        List<NatRuleType> rules = service.getNatRules(service.getEdgeGateway());
        for (int i = 0; i < 3; i++) {
            int internalPort = 1236 + i;
            int externalPort = 5680 + i;
            NatRuleType rule = Iterables.find(rules, NatPredicates.translatedTargetEquals(INTERNAL_MACHINE_IP, internalPort));
            assertEquals(rule.getGatewayNatRule().getOriginalIp(), publicIp);
            assertEquals(rule.getGatewayNatRule().getOriginalPort(), ""+externalPort);
            assertEquals(rule.getGatewayNatRule().getTranslatedIp(), INTERNAL_MACHINE_IP);
            assertEquals(rule.getGatewayNatRule().getTranslatedPort(), ""+internalPort);
        }
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

        return NatService.builder()
                .identity(loc.getIdentity())
                .credential(loc.getCredential())
                .endpoint(convertedUri);
    }
}
