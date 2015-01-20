package brooklyn.networking.vclouddirector;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.util.exceptions.Exceptions;

import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;

public class NatDirectClient implements NatClient {

    /**
     * Returns the mutex to be synchronized on whenever accessing/editing the DNAT rules for a given endpoint.
     */
    private static enum MutexRegistry {
        INSTANCE;
        
        private final Map<String, Object> mutexes = Maps.newLinkedHashMap();
        
        public Object getMutexFor(String endpoint) {
            synchronized (mutexes) {
                Object mutex = mutexes.get(endpoint);
                if (mutex == null) {
                    mutex = new Object();
                    mutexes.put(endpoint, mutex);
                }
                return mutex;
            }
        }
    }

    private final NatService client;
    
    public NatDirectClient(JcloudsLocation loc) {
        String endpoint = transformEndpoint(loc.getEndpoint());

        client = NatService.builder()
                .endpoint(endpoint)
                .identity(loc.getIdentity())
                .credential(loc.getCredential())
                .mutex(MutexRegistry.INSTANCE.getMutexFor(endpoint))
                .build();
    }

    @Override
    public HostAndPort openPortForwarding(PortForwardingConfig args) {
        try {
            return client.openPortForwarding(args);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    @Override
    public HostAndPort closePortForwarding(PortForwardingConfig args) {
        try {
            return client.closePortForwarding(args);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    // jclouds endpoint has suffix "/api"; but VMware SDK wants it without "api"
    public static String transformEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (URISyntaxException e) {
            throw Exceptions.propagate(e);
        } 
    }
}
