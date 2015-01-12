package brooklyn.networking.vclouddirector.natmicroservice;

import java.io.File;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.basic.AbstractEntity;
import brooklyn.networking.vclouddirector.NatServiceDispatcher;
import brooklyn.networking.vclouddirector.natservice.api.NatServiceRestApi;
import brooklyn.networking.vclouddirector.natservice.resources.AbstractRestResource;
import brooklyn.util.crypto.FluentKeySigner;
import brooklyn.util.crypto.SecureKeys;
import brooklyn.util.net.Networking;
import brooklyn.util.text.Identifiers;
import brooklyn.util.text.Strings;

import com.sun.jersey.api.container.filter.GZIPContentEncodingFilter;
import com.sun.jersey.api.core.DefaultResourceConfig;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.spi.container.servlet.ServletContainer;

public class NatMicroService {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractEntity.class);

    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private int port = 8443;
        private String bindAddress = null;
        private String sslCertificate;
        private String keystorePath;
        private String keystorePassword;
        private String keystoreCertAlias;
        private String truststorePath;
        private String trustStorePassword;
        private NatServiceDispatcher dispatcher;
        
        public Builder port(int val) {
            port = val; return this;
        }
        public Builder bindAddress(String val) {
            bindAddress = val; return this;
        }
        public Builder sslCertificate(String val) {
            sslCertificate = val; return this;
        }
        public Builder keystorePath(String val) {
            keystorePath = val; return this;
        }
        public Builder keystorePassword(String val) {
            keystorePassword = val; return this;
        }
        public Builder keystoreCertAlias(String val) {
            keystoreCertAlias = val; return this;
        }
        public Builder truststorePath(String val) {
            truststorePath = val; return this;
        }
        public Builder trustStorePassword(String val) {
            trustStorePassword = val; return this;
        }
        public Builder dispatcher(NatServiceDispatcher val) {
            dispatcher = val; return this;
        }
        public NatMicroService build() {
            return new NatMicroService(this);
        }
    }
    
    private final int port;
    private final String bindAddress;
    
    private final String sslCertificate;
    private final String keystorePath;
    private String keystorePassword;
    private String keystoreCertAlias;
    private final String truststorePath;
    private String trustStorePassword;

    private NatServiceDispatcher dispatcher;
    private Server server;
    private String rootUrl;

    public NatMicroService(Builder builder) {
        this.port = builder.port;
        this.bindAddress = builder.bindAddress;
        this.sslCertificate = builder.sslCertificate;
        this.keystorePath = builder.keystorePath;
        this.keystorePassword = builder.keystorePassword;
        this.keystoreCertAlias = builder.keystoreCertAlias;
        this.truststorePath = builder.truststorePath;
        this.trustStorePassword = builder.trustStorePassword;
        this.dispatcher = builder.dispatcher;
    }
    
    public void start() throws Exception {
        server = new Server();
        https();
        servlet();
        server.start();
    }

    public void join() throws InterruptedException {
        if (server != null) server.join();
    }

    public void stop() throws Exception {
        if (server != null) server.stop();
    }

    public String getRootUrl() {
        return rootUrl;
    }
    
    protected void https() throws KeyStoreException {
        SslContextFactory sslContextFactory = new SslContextFactory();

        if (keystorePath!=null) {
            sslContextFactory.setKeyStorePath(checkFileExists(keystorePath, "keystore"));
            if (Strings.isEmpty(keystorePassword))
                throw new IllegalArgumentException("Keystore password is required and non-empty if keystore is specified.");
            sslContextFactory.setKeyStorePassword(keystorePassword);
            if (Strings.isNonEmpty(keystoreCertAlias))
                sslContextFactory.setCertAlias(keystoreCertAlias);
        } else {
            // TODO allow webconsole keystore & related properties to be set in brooklyn.properties 
            LOG.info("No keystore specified but https enabled; creating a default keystore");
            
            if (Strings.isEmpty(keystoreCertAlias))
                keystoreCertAlias = "web-console";
            
            // if password is blank the process will block and read from stdin !
            if (Strings.isEmpty(keystorePassword)) {
                keystorePassword = Identifiers.makeRandomId(8);
                LOG.debug("created random password "+keystorePassword+" for ad hoc internal keystore");
            }
            
            KeyStore ks = SecureKeys.newKeyStore();
            KeyPair key = SecureKeys.newKeyPair();
            X509Certificate cert = new FluentKeySigner("brooklyn").newCertificateFor("web-console", key);
            ks.setKeyEntry(keystoreCertAlias, key.getPrivate(), keystorePassword.toCharArray(),
                new Certificate[] { cert });
            
            sslContextFactory.setKeyStore(ks);
            sslContextFactory.setKeyStorePassword(keystorePassword);
            sslContextFactory.setCertAlias(keystoreCertAlias);
        }
        if (!Strings.isEmpty(truststorePath)) {
            sslContextFactory.setTrustStore(checkFileExists(truststorePath, "truststore"));
            sslContextFactory.setTrustStorePassword(trustStorePassword);
        }

        SslSocketConnector sslSocketConnector = new SslSocketConnector(sslContextFactory);
        sslSocketConnector.setPort(port);
        sslSocketConnector.setHost(bindAddress);
        server.addConnector(sslSocketConnector);
        
        rootUrl = "https://" + (sslSocketConnector.getHost() == null ? Networking.getLocalHost().getHostAddress() : sslSocketConnector.getHost()) + ":" + port;
    }
    
    protected void servlet() {
        ResourceConfig config = new DefaultResourceConfig();
        // load all our REST API modules, JSON, and Swagger
        for (Object r: NatServiceRestApi.getAllResources())
            config.getSingletons().add(r);

        // Accept gzipped requests and responses
        config.getProperties().put(ResourceConfig.PROPERTY_CONTAINER_REQUEST_FILTERS, GZIPContentEncodingFilter.class.getName());
        config.getProperties().put(ResourceConfig.PROPERTY_CONTAINER_RESPONSE_FILTERS, GZIPContentEncodingFilter.class.getName());
        // configure to match empty path, or any thing which looks like a file path with /assets/ and extension html, css, js, or png
        // and treat that as static content
        config.getProperties().put(ServletContainer.PROPERTY_WEB_PAGE_CONTENT_REGEX, "(/?|[^?]*/assets/[^?]+\\.[A-Za-z0-9_]+)");
        // and anything which is not matched as a servlet also falls through (but more expensive than a regex check?)
        config.getFeatures().put(ServletContainer.FEATURE_FILTER_FORWARD_ON_404, true);
        
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ServletContainer servletContainer = new ServletContainer(config);
        ServletHolder servletHolder = new ServletHolder(servletContainer);
        servletHolder.setInitParameter(JSONConfiguration.FEATURE_POJO_MAPPING, Boolean.TRUE.toString());
        context.addServlet(servletHolder,"/*");
        context.setContextPath("/");
        context.setAttribute(AbstractRestResource.NAT_SERVICE_DISPATCHER, dispatcher);
//        context.setAttribute(JSONConfiguration.FEATURE_POJO_MAPPING, true);
        server.setHandler(context);
    }

//    public class HelloServlet extends HttpServlet {
//        private String greeting="Hello World";
//        
//        public HelloServlet() {
//        }
//        
//        public HelloServlet(String greeting) {
//            this.greeting=greeting;
//        }
//        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
//            response.setContentType("text/html");
//            response.setStatus(HttpServletResponse.SC_OK);
//            response.getWriter().println("<h1>"+greeting+"</h1>");
//            response.getWriter().println("session=" + request.getSession(true).getId());
//        }
//    }
//    
    private String checkFileExists(String path, String name) {
        if(!new File(path).exists()){
            throw new IllegalArgumentException("Could not find "+name+": "+path);
        }
        return path;
    }
}
