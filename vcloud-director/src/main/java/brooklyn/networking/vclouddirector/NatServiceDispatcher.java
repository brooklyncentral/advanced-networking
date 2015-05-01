package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

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

import brooklyn.location.PortRange;
import brooklyn.location.basic.PortRanges;
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
        private Map<String, EndpointConfig> endpoints = Maps.newLinkedHashMap();
        private PortRange defaultPortRange = PortRanges.ANY_HIGH_PORT;
        private NatService.NatServiceFactory natServiceFactory = new NatService.NatServiceFactory();
        
        public Builder endpoint(String endpoint, EndpointConfig trustConfig) {
            endpoints.put(checkNotNull(endpoint, "endpoint"), checkNotNull(trustConfig, "trustConfig"));
            return this;
        }
        public Builder endpoints(Map<String, EndpointConfig> vals) {
            this.endpoints.putAll(vals); return this;
        }
        public Builder portRange(PortRange val) {
            this.defaultPortRange = val; return this;
        }
        public Builder logLevel(java.util.logging.Level val) {
            this.logLevel = val; return this;
        }
        public Builder natServiceFactory(NatService.NatServiceFactory val) {
            natServiceFactory = checkNotNull(val, "natServiceFactory");
            return this;
        }
        public NatServiceDispatcher build() {
            return new NatServiceDispatcher(this);
        }
    }
    
    public static class EndpointConfig {
        private final PortRange portRange;
        private final String trustStore;
        private final String trustStorePassword;
        
        public EndpointConfig(PortRange portRange, String trustStore, String trustStorePassword) {
            this.portRange = portRange;
            this.trustStore = trustStore;
            this.trustStorePassword = trustStorePassword;
        }
        
        @Override
        public int hashCode() {
            return Objects.hashCode(portRange, trustStore, trustStorePassword);
        }
        
        @Override
        public boolean equals(Object obj) {
            return obj instanceof EndpointConfig
                    && Objects.equal(portRange, ((EndpointConfig)obj).portRange)
                    && Objects.equal(trustStore, ((EndpointConfig)obj).trustStore) 
                    && Objects.equal(trustStorePassword, ((EndpointConfig)obj).trustStorePassword);
        }
        
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("trustStore", trustStore)
                    .add("trustStorePassword", (trustStorePassword == null) ? null : "********")
                    .add("portRange", portRange)
                    .toString();
        }
    }
    
    static class Credentials {
        final String endpoint;
        @Nullable final String vDC;
        final String identity;
        final String credential;

        @Deprecated
        public Credentials(String endpoint, String identity, String credential) {
            this(endpoint, null, identity, credential);
        }
        
        public Credentials(String endpoint, @Nullable String vDC, String identity, String credential) {
            this.endpoint = checkNotNull(endpoint, "endpoint");
            this.vDC = vDC;
            this.identity = checkNotNull(identity, "identity");
            this.credential = checkNotNull(credential, "credential");
        }
        
        @Override
        public int hashCode() {
            return Objects.hashCode(endpoint, vDC, identity, credential);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Credentials)) return false;
            Credentials o = (Credentials) obj;
            return identity.equals(o.identity) && endpoint.equals(o.endpoint) && credential.equals(o.credential) 
                    && Objects.equal(vDC, o.vDC);
        }
        
        @Override
        public String toString() {
            return identity+":********@"+endpoint;
        }
    }
    
    /**
     * The EdgeGateway that the NAT Rules belong to (used to identify the appropriate lock, so
     * that concurrent requests on a given EdgeGateway are submitted sequentially).
     */
    static class EdgeGatewayIdentifier {
        private final String endpoint;
        private final String vOrg;
        private final String vDC;
        
        public EdgeGatewayIdentifier(String endpoint, String vOrg, String vDC) {
            this.endpoint = checkNotNull(endpoint, "endpoint");
            this.vOrg = vOrg;
            this.vDC = vDC;
        }
        
        public EdgeGatewayIdentifier(Credentials creds) {
            this.endpoint = checkNotNull(creds, "creds").endpoint;
            this.vOrg = (creds.identity.contains("@")) ? creds.identity.substring(creds.identity.lastIndexOf("@") + 1) : null;
            this.vDC = creds.vDC;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(endpoint, vOrg, vDC);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof EdgeGatewayIdentifier)) return false;
            EdgeGatewayIdentifier o = (EdgeGatewayIdentifier) obj;
            return Objects.equal(endpoint, o.endpoint) && Objects.equal(vOrg, o.vOrg) 
                    && Objects.equal(vDC, o.vDC);
        }
        
        @Override
        public String toString() {
            return Objects.toStringHelper(this).omitNullValues()
                    .add("endpoint", endpoint)
                    .add("vOrg", vOrg)
                    .add("vDC", vDC)
                    .toString();
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
        
        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("type", actionType).add("args", args).toString();
        }
    }
    
    private final static class UncaughtExceptionHandlerImplementation implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            LOG.error("Uncaught exception in thread "+t.getName(), e);
        }
    }

    private final Map<String, EndpointConfig> endpoints;
    private final PortRange defaultPortRange;
    private final Level logLevel;
    private final NatService.NatServiceFactory natServiceFactory;
    private final ExecutorService executor;

    private final Map<EdgeGatewayIdentifier, SingleThreadedScheduler> workers = Maps.newLinkedHashMap();
    private final Multimap<Credentials, NatServiceAction> actionQueues = ArrayListMultimap.create(); 
    private final Map<Credentials, NatService> clients = Maps.newLinkedHashMap();
    private final Map<EdgeGatewayIdentifier, Object> mutexes = Maps.newConcurrentMap();
    
    public NatServiceDispatcher(Builder builder) {
        endpoints = ImmutableMap.copyOf(builder.endpoints);
        defaultPortRange = builder.defaultPortRange;
        logLevel = builder.logLevel;
        natServiceFactory = builder.natServiceFactory;
        
        executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
                .setNameFormat("natservice-execmanager-%d")
                .setUncaughtExceptionHandler(new UncaughtExceptionHandlerImplementation())
                .setDaemon(true)
                .build());
    }

    public void close() {
        executor.shutdownNow();
    }
    
    public List<NatRuleType> getNatRules(String endpoint, String vDC, String identity, String credential) throws VCloudException {
        NatService service = getService(new Credentials(endpoint, vDC, identity, credential));
        return service.getNatRules();
    }
    
    public HostAndPort openPortForwarding(String endpoint, String vDC, String identity, String credential, PortForwardingConfig args) {
        Credentials creds = new Credentials(endpoint, vDC, identity, credential);
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

    public HostAndPort closePortForwarding(String endpoint, String vDC, String identity, String credential, PortForwardingConfig args) throws VCloudException {
        Credentials creds = new Credentials(endpoint, vDC, identity, credential);
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
        EdgeGatewayIdentifier workerKey = new EdgeGatewayIdentifier(creds);
        SingleThreadedScheduler worker = workers.get(workerKey);
        if (worker == null) {
            worker = new SingleThreadedScheduler();
            worker.injectExecutor(executor);
            workers.put(workerKey, worker);
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
            // TODO Handling concurrent conflicting open+close for the same port needs more
            // attention; we won't hit the scenario of "removing from toOpen" below because 
            // callers are using portRange when requesting that a port be opened. However,
            // there is still an issue for service.updatePortForwarding not being written to
            // expect being told to open and close the same thing. It will probably do the
            // open and then the close.
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
            NatService.UpdateResult updated = service.updatePortForwarding(delta);
            
            for (NatServiceAction action : actions) {
                switch (action.actionType) {
                case OPEN:
                    // The original action may not have specified the port; get that from the result.
                    // check that the result looks sane (i.e. unlikely that order has messed up etc).
                    PortForwardingConfig result = updated.getOpened().get(action.args);
                    if (result == null) {
                        String msg = "No result for opening port-mapping "+action.args+" (result "+updated.getOpened()+")";
                        LOG.warn(msg);
                        action.setException(new IllegalStateException(msg));
                    } else {
                        if (action.args.publicEndpoint.hasPort()) {
                            checkState(result.publicEndpoint.equals(action.args.publicEndpoint), 
                                    "result=%s; action=%s", result.publicEndpoint, action);
                        } else {
                            checkState(result.publicEndpoint.getHostText().equals(action.args.publicEndpoint.getHostText()), 
                                    "result=%s; action=%s", result.publicEndpoint, action);
                            checkState(result.publicEndpoint.hasPort(), 
                                    "result=%s; action=%s", result.publicEndpoint, action);
                        }
                    }
                    action.set(result.publicEndpoint);
                    break;
                case CLOSE:
                    action.set(action.args.publicEndpoint);
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
        EdgeGatewayIdentifier mutexKey = new EdgeGatewayIdentifier(creds);
        synchronized (mutexes) {
            mutex = mutexes.get(mutexKey);
            if (mutex == null) {
                mutex = new Object();
                mutexes.put(mutexKey, mutex);
            }
        }
        
        synchronized (clients) {
            NatService result = clients.get(creds);
            if (result == null) {
                EndpointConfig endpointConfig = endpoints.get(creds.endpoint);
                if (endpointConfig == null) {
                    throw new IllegalArgumentException("Unknown endpoint "+creds.endpoint+" (identity "+creds.identity+")");
                }
                PortRange portRange = (endpointConfig.portRange == null) ? defaultPortRange : endpointConfig.portRange;
                
                result = natServiceFactory.builder()
                        .identity(creds.identity)
                        .credential(creds.credential)
                        .endpoint(creds.endpoint)
                        .trustStore(endpointConfig.trustStore)
                        .trustStorePassword(endpointConfig.trustStorePassword)
                        .portRange(portRange)
                        .logLevel(logLevel)
                        .mutex(mutex)
                        .build();
                clients.put(creds, result);
            }
            return result;
        }
    }
}
