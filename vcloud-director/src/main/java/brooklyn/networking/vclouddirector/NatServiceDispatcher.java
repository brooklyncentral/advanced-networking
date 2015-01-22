package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.task.SingleThreadedScheduler;

import com.google.common.base.Objects;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.vmware.vcloud.api.rest.schema.NatRuleType;
import com.vmware.vcloud.sdk.VCloudException;

/**
 * Dispatches NAT service updates to handle concurrent access by multiple users
 * to a given endpoint.
 * 
 * The threading model is (conceptually) one thread per endpoint. This is like having
 * a single-threaded executor per endpoint.
 * 
 * A queue of pending actions (for open/close port forwarding) is maintained per user. When
 * a new request is made, it triggers a job to be submitted for the associated endpoint.
 * When that job executes, it gets all the queued actions for that endpoint+user, and 
 * executes them all together.
 */
public class NatServiceDispatcher {

    // TODO Because of batching, if there is one bad request then all queued requests with
    // the same credentials will also fail when we invoke NatService.updatePortMapping.

    // TODO How should we ensure that NatService instances are disposed of, when they are
    // not used for a while? Should it be a cache with an eviction policy?

    private static final Logger LOG = LoggerFactory.getLogger(NatServiceDispatcher.class);

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Level logLevel;
        private Map<String, TrustConfig> endpoints = Maps.newLinkedHashMap();
        
        public Builder endpoint(String endpoint, TrustConfig trustConfig) {
            endpoints.put(checkNotNull(endpoint, "endpoint"), checkNotNull(trustConfig, "trustConfig"));
            return this;
        }
        public Builder endpoints(Map<String, TrustConfig> vals) {
            this.endpoints.putAll(vals); return this;
        }
        public Builder logLevel(java.util.logging.Level val) {
            this.logLevel = val; return this;
        }
        public NatServiceDispatcher build() {
            return new NatServiceDispatcher(this);
        }
    }
    
    public static class TrustConfig {
        private String trustStore;
        private String trustStorePassword;
        
        public TrustConfig(String trustStore, String trustStorePassword) {
            this.trustStore = trustStore;
            this.trustStorePassword = trustStorePassword;
        }
        
        @Override
        public int hashCode() {
            return Objects.hashCode(trustStore, trustStorePassword);
        }
        
        @Override
        public boolean equals(Object obj) {
            return obj instanceof TrustConfig && trustStore.equals(((TrustConfig)obj).trustStore) 
                    && trustStorePassword.equals(((TrustConfig)obj).trustStorePassword);
        }
        
        @Override
        public String toString() {
            return trustStore+":********)";
        }
    }
    
    static class Credentials {
        final String endpoint;
        final String identity;
        final String credential;
        
        public Credentials(String endpoint, String identity, String credential) {
            this.endpoint = checkNotNull(endpoint, "endpoint");
            this.identity = checkNotNull(identity, "identity");
            this.credential = checkNotNull(credential, "credential");
        }
        
        @Override
        public int hashCode() {
            return Objects.hashCode(endpoint, identity, credential);
        }
        
        @Override
        public boolean equals(Object obj) {
            return obj instanceof Credentials && identity.equals(((Credentials)obj).identity) 
                    && endpoint.equals(((Credentials)obj).endpoint) && credential.equals(((Credentials)obj).credential);
        }
        
        @Override
        public String toString() {
            return identity+":********@"+endpoint;
        }
    }
    
    static class NatServiceAction extends AbstractFuture<HostAndPort> {
        enum ActionType {
            OPEN,
            CLOSE;
        }

        final ActionType actionType;
        final PortForwardingConfig args;
        
        public NatServiceAction(ActionType actionType, PortForwardingConfig args) {
            this.actionType = actionType;
            this.args = args;
        }
        
        @Override
        public boolean set(@Nullable HostAndPort value) {
            return super.set(value);
        }

        @Override
        public boolean setException(Throwable throwable) {
            return super.setException(throwable);
        }
    }
    
    private final static class UncaughtExceptionHandlerImplementation implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            LOG.error("Uncaught exception in thread "+t.getName(), e);
        }
    }

    private final Map<String, TrustConfig> endpoints;
    private final Level logLevel;
    
    private final ExecutorService executor;

    private final Map<String, SingleThreadedScheduler> workers = Maps.newLinkedHashMap();
    private final Multimap<Credentials, NatServiceAction> actionQueues = ArrayListMultimap.create(); 
    private final Map<Credentials, NatService> clients = Maps.newLinkedHashMap();
    private final Map<String, Object> mutexes = Maps.newConcurrentMap(); // keyed by endpoint URL
    
    public NatServiceDispatcher(Builder builder) {
        endpoints = ImmutableMap.copyOf(builder.endpoints );
        logLevel = builder.logLevel;
        
        executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
                .setNameFormat("natservice-execmanager-%d")
                .setUncaughtExceptionHandler(new UncaughtExceptionHandlerImplementation())
                .setDaemon(true)
                .build());
    }

    public void close() {
        executor.shutdownNow();
    }
    
    public List<NatRuleType> getNatRules(String endpoint, String identity, String credential) throws VCloudException {
        NatService service = getService(new Credentials(endpoint, identity, credential));
        return service.getNatRules();
    }
    
    public HostAndPort openPortForwarding(String endpoint, String identity, String credential, PortForwardingConfig args) {
        Credentials creds = new Credentials(endpoint, identity, credential);
        NatServiceAction action = new NatServiceAction(NatServiceAction.ActionType.OPEN, args);
        synchronized (actionQueues) {
            actionQueues.put(creds, action);
        }
        triggerExecutor(creds);
        try {
            return action.get();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    public HostAndPort closePortForwarding(String endpoint, String identity, String credential, PortForwardingConfig args) throws VCloudException {
        Credentials creds = new Credentials(endpoint, identity, credential);
        NatServiceAction action = new NatServiceAction(NatServiceAction.ActionType.CLOSE, args);
        synchronized (actionQueues) {
            actionQueues.put(creds, action);
        }
        triggerExecutor(creds);
        try {
            return action.get();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    protected void triggerExecutor(final Credentials creds) {
        SingleThreadedScheduler worker = workers.get(creds.endpoint);
        if (worker == null) {
            worker = new SingleThreadedScheduler();
            worker.injectExecutor(executor);
            workers.put(creds.endpoint, worker);
        }
        worker.submit(new Callable<Void>() {
            public Void call() {
                try {
                    executeActions(creds);
                    return null;
                } catch (Exception e) {
                    LOG.warn("Problem executing action to modify NAT rules for "+creds.identity+" @ "+creds.credential, e);
                    throw Exceptions.propagate(e);
                }
            }
        });
    }

    protected void executeActions(Credentials creds) throws VCloudException {
        Collection<NatServiceAction> actions;
        synchronized (actionQueues) {
            actions = actionQueues.removeAll(creds);
        }
        try {
            Set<PortForwardingConfig> toOpen = Sets.newLinkedHashSet();
            Set<PortForwardingConfig> toClose = Sets.newLinkedHashSet();
            for (NatServiceAction action : actions) {
                switch (action.actionType) {
                case OPEN:
                    toOpen.add(action.args);
                    toClose.remove(action.args);
                    break;
                case CLOSE:
                    toClose.add(action.args);
                    toOpen.remove(action.args);
                    break;
                default:
                    throw new UnsupportedOperationException("Unexpected action type: "+action.actionType);
                }
            }
            NatService service = getService(creds);
            
            NatService.Delta delta = new NatService.Delta().toOpen(toOpen).toClose(toClose);
            service.updatePortForwarding(delta);
            
            for (NatServiceAction action : actions) {
                switch (action.actionType) {
                case OPEN:
                    action.set(HostAndPort.fromParts(action.args.publicIp, action.args.publicPort));
                    break;
                case CLOSE:
                    action.set(HostAndPort.fromParts(action.args.publicIp, action.args.publicPort));
                    break;
                }
            }
            
        } catch (Throwable e) {
            for (NatServiceAction action : actions) {
                action.setException(e);
            }
            throw Exceptions.propagate(e);
        }
    }

    protected NatService getService(Credentials creds) throws VCloudException {
        Object mutex;
        synchronized (mutexes) {
            mutex = mutexes.get(creds.endpoint);
            if (mutex == null) {
                mutex = new Object();
                mutexes.put(creds.endpoint, mutex);
            }
        }
        
        synchronized (clients) {
            NatService result = clients.get(creds);
            if (result == null) {
                TrustConfig trustConfig = endpoints.get(creds.endpoint);
                if (trustConfig == null) {
                    throw new IllegalArgumentException("Unknown endpoint "+creds.endpoint+" (identity "+creds.identity+")");
                }
                result = NatService.builder()
                        .identity(creds.identity)
                        .credential(creds.credential)
                        .endpoint(creds.endpoint)
                        .trustStore(trustConfig.trustStore)
                        .trustStorePassword(trustConfig.trustStorePassword)
                        .logLevel(logLevel)
                        .mutex(mutex)
                        .build();
                clients.put(creds, result);
            }
            return result;
        }
    }
}
