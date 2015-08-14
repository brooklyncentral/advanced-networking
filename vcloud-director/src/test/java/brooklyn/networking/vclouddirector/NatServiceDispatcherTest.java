package brooklyn.networking.vclouddirector;

import static org.testng.Assert.assertEquals;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.vmware.vcloud.api.rest.schema.NatRuleType;
import com.vmware.vcloud.sdk.VCloudException;
import com.vmware.vcloud.sdk.VcloudClient;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.location.PortRange;

import brooklyn.networking.vclouddirector.NatService.Builder;
import brooklyn.networking.vclouddirector.NatServiceDispatcher.EndpointConfig;

public class NatServiceDispatcherTest {

    private Map<String, EndpointConfig> endpoints = ImmutableMap.of(
            "http://127.0.0.1:1234", new EndpointConfig(null, null, null),
            "http://127.0.0.1:1235", new EndpointConfig(null, null, null),
            "http://127.0.0.1:1236", new EndpointConfig(null, null, null));
    
    private RecordingNatServiceFactory natServiceFactory;
    private NatServiceDispatcher dispatcher;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        natServiceFactory = new RecordingNatServiceFactory();
        
        dispatcher = NatServiceDispatcher.builder()
                .natServiceFactory(natServiceFactory)
                .endpoints(endpoints)
                .build();
    }

    @Test
    public void testSameVdcGetsSameMutex() throws Exception {
        String endpoint = Iterables.getFirst(endpoints.keySet(), null);
        for (int i = 0; i < 3; i++) {
            dispatcher.openPortForwarding(endpoint, "myvdc", "myid"+i+"@myorg", "mycred"+i, new PortForwardingConfig()
                    .targetEndpoint(HostAndPort.fromParts("myhost", 1234+i))
                    .publicEndpoint(HostAndPort.fromHost("requestedpublichost")));
        }
        
        assertEquals(getMutexCount(), 1);
    }

    @Test
    public void testSameVorgWithNullVdcGetsSameMutex() throws Exception {
        String endpoint = Iterables.getFirst(endpoints.keySet(), null);
        for (int i = 0; i < 3; i++) {
            dispatcher.openPortForwarding(endpoint, null, "myid"+i+"@myorg", "mycred"+i, new PortForwardingConfig()
                    .targetEndpoint(HostAndPort.fromParts("myhost", 1234+i))
                    .publicEndpoint(HostAndPort.fromHost("requestedpublichost")));
        }
        
        assertEquals(getMutexCount(), 1);
    }

    @Test
    public void testDifferentEndpointsHaveDifferentMutexes() throws Exception {
        String vDC = null;
        int port = 1234;
        for (String endpoint : endpoints.keySet()) {
            dispatcher.openPortForwarding(endpoint, vDC, "myid@myorg", "mycred", new PortForwardingConfig()
                    .targetEndpoint(HostAndPort.fromParts("myhost", port++))
                    .publicEndpoint(HostAndPort.fromHost("requestedpublichost")));
        }
        
        assertEquals(getMutexCount(), endpoints.size());
    }
    
    @Test
    public void testDifferentVdcsHaveDifferentMutexes() throws Exception {
        String endpoint = Iterables.getFirst(endpoints.keySet(), null);
        for (int i = 0; i < 3; i++) {
            dispatcher.openPortForwarding(endpoint, "myvdc-"+i, "myid@myorg", "mycred", new PortForwardingConfig()
                    .targetEndpoint(HostAndPort.fromParts("myhost", 1234+i))
                    .publicEndpoint(HostAndPort.fromHost("requestedpublichost")));
        }
        
        assertEquals(getMutexCount(), endpoints.size());
    }

    @Test
    public void testDifferentVorgsHaveDifferentMutexes() throws Exception {
        String endpoint = Iterables.getFirst(endpoints.keySet(), null);
        for (int i = 0; i < 3; i++) {
            dispatcher.openPortForwarding(endpoint, null, "myid@myorg"+i, "mycred", new PortForwardingConfig()
                    .targetEndpoint(HostAndPort.fromParts("myhost", 1234+i))
                    .publicEndpoint(HostAndPort.fromHost("requestedpublichost")));
        }
        
        assertEquals(getMutexCount(), endpoints.size());
    }

    private int getMutexCount() {
        Set<Object> mutexes = Sets.newLinkedHashSet();
        for (RecordingNatService service : natServiceFactory.services.values()) {
            mutexes.add(service.getMutex());
        }
        return mutexes.size();
    }
    
    
    ///////////////////////////////////////////////////////////////////////////////////////
    // Plumbing below to intercept NatService calls, returning plausible looking results //
    ///////////////////////////////////////////////////////////////////////////////////////
    
    public static class RecordingNatServiceFactory extends NatService.NatServiceFactory {
        public final Map<RecordingNatServiceBuilder, RecordingNatService> services = Maps.newConcurrentMap();
        
        public class RecordingNatServiceBuilder extends NatService.Builder {
            @Override
            public NatService build() {
                RecordingNatService result = new RecordingNatService(this);
                services.put(this, result);
                return result;
            }
        }

        @Override
        public Builder builder() {
            return new RecordingNatServiceBuilder();
        }
    }
    
    // TODO Better to use Mockito?!
    public static class RecordingNatService extends NatService {
        public final Builder builder;
        int nextPort = 1024;
        
        public RecordingNatService(Builder builder) {
            super(builder);
            this.builder = builder;
        }

        @Override
        protected VcloudClient newVcloudClient() {
            return null;
        }

        @Override
        protected VcloudClient newVcloudClient(String endpoint, String identity, String credential, String trustStore, String trustStorePassword, Level logLevel) {
            return null;
        }
        
        @Override
        public List<NatRuleType> getNatRules() throws VCloudException {
            return ImmutableList.of();
        }

        @Override
        public HostAndPort openPortForwarding(PortForwardingConfig args) throws VCloudException {
            return openPortForwarding(ImmutableList.of(args)).get(0);
        }
        
        @Override
        public List<HostAndPort> openPortForwarding(Iterable<PortForwardingConfig> args) throws VCloudException {
            List<HostAndPort> result = Lists.newArrayList();
            for (PortForwardingConfig arg : args) {
                HostAndPort publicEndpoint = arg.publicEndpoint == null 
                        ? HostAndPort.fromParts("mypublichost", (nextPort++)) 
                        : arg.publicEndpoint.hasPort() 
                                ? arg.publicEndpoint 
                                : HostAndPort.fromParts(arg.publicEndpoint.getHostText(), nextPort++);
                result.add(publicEndpoint);
            }
            return result;
        }

        @Override
        public HostAndPort closePortForwarding(PortForwardingConfig args) throws VCloudException {
            return closePortForwarding(ImmutableList.of(args)).get(0);
        }
        
        @Override
        public List<HostAndPort> closePortForwarding(Iterable<PortForwardingConfig> args) throws VCloudException {
            List<HostAndPort> result = Lists.newArrayList();
            for (PortForwardingConfig arg : args) {
                result.add(arg.publicEndpoint);
            }
            return result;
        }
        
        @Override
        public UpdateResult updatePortForwarding(Delta delta) throws VCloudException {
            UpdateResult result = new UpdateResult();
            for (PortForwardingConfig arg : delta.toOpen) {
                PortForwardingConfig opened = new PortForwardingConfig()
                        .protocol(arg.protocol)
                        .publicEndpoint(arg.publicEndpoint == null 
                                ? HostAndPort.fromParts("mypublichost", (nextPort++)) 
                                : arg.publicEndpoint.hasPort() 
                                        ? arg.publicEndpoint 
                                        : HostAndPort.fromParts(arg.publicEndpoint.getHostText(), nextPort++))
                        .targetEndpoint(arg.targetEndpoint);
                result.opened(arg, opened);
            }
            for (PortForwardingConfig arg : delta.toClose) {
                result.closed(arg, arg);
            }
            return result;
        }

        @Override
        public Set<Integer> getUsedPublicPorts(Iterable<NatRuleType> existingRules) {
            return ImmutableSet.<Integer>of();
        }
        
        @Override
        public int findAvailablePort(PortRange portRange, Collection<Integer> usedPorts) {
            throw new UnsupportedOperationException();
        }
        
        @Override
        public void enableNatService() throws VCloudException {
            throw new UnsupportedOperationException();
        }
    }
}
