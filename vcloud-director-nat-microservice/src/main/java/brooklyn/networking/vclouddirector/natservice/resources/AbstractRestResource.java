package brooklyn.networking.vclouddirector.natservice.resources;

import javax.servlet.ServletContext;
import javax.ws.rs.core.Context;

import brooklyn.networking.vclouddirector.NatServiceDispatcher;

import com.google.common.annotations.VisibleForTesting;

public abstract class AbstractRestResource {

    /** used to hold the instance of NotServiceDispatcher which should be used */
    public static final String NAT_SERVICE_DISPATCHER = NatServiceDispatcher.class.getName();

    @VisibleForTesting
    public static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZ";
    
    // can be injected by jersey when ManagementContext in not injected manually
    // (seems there is no way to make this optional so note it _must_ be injected;
    // most of the time that happens for free, but with test framework it doesn't,
    // so we have set up a NullServletContextProvider in our tests) 
    @Context ServletContext servletContext;
    
    private NatServiceDispatcher dispatcher;

    @VisibleForTesting
    public void injectNatServiceDispatcher(NatServiceDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }
    
    protected NatServiceDispatcher dispatcher() {
        if (dispatcher == null) {
            dispatcher = (NatServiceDispatcher) servletContext.getAttribute(NAT_SERVICE_DISPATCHER);
        }
        if (dispatcher != null) return dispatcher;
        
        throw new IllegalStateException("NatServiceDispatcher not supplied for Jersey Resource "+this);
    }
}
