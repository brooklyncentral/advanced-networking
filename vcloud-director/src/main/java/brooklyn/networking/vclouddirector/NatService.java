package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.xml.bind.JAXBElement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.net.Protocol;
import brooklyn.util.net.Urls;

import com.google.api.client.repackaged.com.google.common.base.Objects;
import com.google.common.annotations.Beta;
import com.google.common.net.HostAndPort;
import com.vmware.vcloud.api.rest.schema.GatewayFeaturesType;
import com.vmware.vcloud.api.rest.schema.GatewayNatRuleType;
import com.vmware.vcloud.api.rest.schema.NatRuleType;
import com.vmware.vcloud.api.rest.schema.NatServiceType;
import com.vmware.vcloud.api.rest.schema.NetworkServiceType;
import com.vmware.vcloud.api.rest.schema.ObjectFactory;
import com.vmware.vcloud.api.rest.schema.ReferenceType;
import com.vmware.vcloud.sdk.ReferenceResult;
import com.vmware.vcloud.sdk.Task;
import com.vmware.vcloud.sdk.VCloudException;
import com.vmware.vcloud.sdk.VcloudClient;
import com.vmware.vcloud.sdk.admin.EdgeGateway;
import com.vmware.vcloud.sdk.admin.extensions.ExtensionQueryService;
import com.vmware.vcloud.sdk.admin.extensions.VcloudAdminExtension;
import com.vmware.vcloud.sdk.constants.Version;
import com.vmware.vcloud.sdk.constants.query.QueryReferenceType;

@Beta
public class NatService {

	private static final Logger LOG = LoggerFactory.getLogger(NatService.class);
	
	private static final String NAT_SERVICE_TYPE = "NatServiceType";

	private static final String NETWORK_NAME = "d4p5-ext";

	public static Builder builder() {
		return new Builder();
	}

    public static class Builder {
        private String identity;
        private String credential;
        private String endpoint;
        private String trustStore;
        private String trustStorePassword;
        
        public Builder location(JcloudsLocation val) {
        	identity(val.getIdentity());
        	credential(val.getCredential());
        	endpoint(transformEndpoint(val.getEndpoint()));
        	return this;
        }
        private String transformEndpoint(String val) {
            // jclouds endpoint has suffix "/api"; but VMware SDK wants it without "api"
            URI uri = URI.create(val);
            try {
                return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null).toString();
            } catch (URISyntaxException e) {
                throw Exceptions.propagate(e);
            } 
        }
        public Builder identity(String val) {
        	this.identity = val; return this;
        }
        public Builder credential(String val) {
        	this.credential = val; return this;
        }
        public Builder endpoint(String val) {
        	this.endpoint = val; return this;
        }
        public Builder trustStore(String val) {
        	this.trustStore = val; return this;
        }
        public Builder trustStorePassword(String val) {
        	this.trustStorePassword = val; return this;
        }
    	public NatService build() {
    		return new NatService(this);
    	}
    }
    
	private final VcloudClient client;
	private final String baseUrl; // e.g. "https://p5v1-vcd.vchs.vmware.com:443";

    public NatService(Builder builder) {
    	client = newVcloudClient(checkNotNull(builder.endpoint, "endpoint"), checkNotNull(builder.identity, "identity"), 
    			checkNotNull(builder.credential, "credential"), builder.trustStore, builder.trustStorePassword);
    	baseUrl = builder.endpoint;
    }

    public static class OpenPortForwardingConfig {
    	private Protocol protocol;
    	private HostAndPort target;
    	private String networkId;
    	private String publicIp;
    	private Integer publicPort;
    	
    	public OpenPortForwardingConfig protocol(Protocol val) {
    		this.protocol = val; return this;
    	}
    	public OpenPortForwardingConfig networkId(String val) {
    		this.networkId = val; return this;
    	}
    	public OpenPortForwardingConfig target(HostAndPort val) {
    		this.target = val; return this;
    	}
    	public OpenPortForwardingConfig publicIp(String val) {
    		this.publicIp = val; return this;
    	}
    	public OpenPortForwardingConfig publicPort(int val) {
    		this.publicPort = val; return this;
    	}
        public void checkValid() {
        	checkNotNull(protocol, "protocol");
        	checkNotNull(target, "target");
        	checkNotNull(networkId, "networkId");
        	checkNotNull(publicIp, "publicIp");
            checkNotNull(publicPort, publicPort);
        }
    	@Override
    	public String toString() {
    		return Objects.toStringHelper(this).add("protocol", protocol).add("target", target).add("networkId", networkId)
    				.add("publicIp", publicIp).add("publicPort", publicPort).toString();
    	}
    }
    public HostAndPort openPortForwarding(OpenPortForwardingConfig args) throws VCloudException {
    	args.checkValid();
    	if (LOG.isDebugEnabled()) LOG.debug("Opening port forwarding at {}: {}", baseUrl, args);
    	
        EdgeGateway edgeGateway = getEdgeGateway();
        List<NatRuleType> natRules = getNatRules(edgeGateway);

    	String networkUrl = Urls.mergePaths(baseUrl, "api/admin/network", args.networkId);

        ReferenceType interfaceRef = generateReference(networkUrl, NETWORK_NAME, "application/vnd.vmware.admin.network+xml");

        GatewayNatRuleType gatewayNatRule = generateGatewayNatRule(
        		args.protocol, 
        		HostAndPort.fromParts(args.publicIp, args.publicPort), 
        		args.target, 
        		interfaceRef);
        NatRuleType dnatRule = generateDnatRule(true, gatewayNatRule);

        // append DNAT rule to NAT service
        // TODO Should retrieve existing NAT Service object, rather than creating a new one.
        //      We lose/override existing config potentially.
        natRules.add(dnatRule);
        NatServiceType natService = new NatServiceType();
        natService.setIsEnabled(true);
        natService.getNatRule().addAll(natRules);
        ObjectFactory objectFactory = new ObjectFactory();
        JAXBElement<NetworkServiceType> serviceType = objectFactory.createNetworkService(natService);
        GatewayFeaturesType gatewayFeatures = new GatewayFeaturesType();
        gatewayFeatures.getNetworkService().add(serviceType);
        
        // Execute task
        Task task = edgeGateway.configureServices(gatewayFeatures);
        if (task != null) {
            try {
                task.waitForTask(0);
            } catch (TimeoutException e) {
                throw Exceptions.propagate(e);
            }
        }

        // Find the newly created rule, and find the translated (i.e. public) address
        List<NatRuleType> rules = getNatRules(edgeGateway);
        for (NatRuleType rule : rules) {
        	GatewayNatRuleType gatewayRule = rule.getGatewayNatRule();
        	if (gatewayRule != null && args.target.getHostText().equals(gatewayRule.getTranslatedIp()) 
        			&& Integer.toString(args.target.getPort()).equals(gatewayRule.getTranslatedPort())) {
        		return HostAndPort.fromParts(gatewayRule.getOriginalIp(), Integer.parseInt(gatewayRule.getOriginalPort()));
        	}
        }
        throw new IllegalStateException("Cannot find translated host:port for Gateway NAT Rule added for target "+args.target);
    }

    protected EdgeGateway getEdgeGateway() throws VCloudException {
        List<ReferenceType> edgeGatewayRef = queryEdgeGateways();
        return EdgeGateway.getEdgeGatewayById(client, edgeGatewayRef.get(0).getId());
    }
    
    protected List<NatRuleType> getNatRules(EdgeGateway edgeGateway) {
        List<JAXBElement<? extends NetworkServiceType>> services = edgeGateway
                .getResource()
                .getConfiguration()
                .getEdgeGatewayServiceConfiguration()
                .getNetworkService();
        
		for (JAXBElement<? extends NetworkServiceType> service : services) {
            if (service.getDeclaredType().getSimpleName().equals(NAT_SERVICE_TYPE)) {
                return ((NatServiceType) service.getValue()).getNatRule();
            }
        }
        return new ArrayList<NatRuleType>();
    }

    private List<ReferenceType> queryEdgeGateways() throws VCloudException {
        // Getting the VcloudAdminExtension
        VcloudAdminExtension adminExtension = client.getVcloudAdminExtension();

        // Getting the Admin Extension Query Service.
        ExtensionQueryService queryService = adminExtension.getExtensionQueryService();
        ReferenceResult referenceResult = queryService.queryReferences(QueryReferenceType.EDGEGATEWAY);
        return referenceResult.getReferences();
    }

    // FIXME Don't set sysprop as could affect all other activities of the JVM!
    private VcloudClient newVcloudClient(String arg0, String identity, String credential, String trustStore, String trustStorePassword) {
    	try {
    		// Client login
            VcloudClient vcloudClient = null;
            boolean versionFound = false;
            for (Version version : new Version[]{Version.V5_5, Version.V5_1, Version.V1_5}) {
                try {
                    if (!versionFound) {
                        vcloudClient = new VcloudClient(arg0, version);
                        LOG.info("VCloudClient - Trying login using " + version);
                        vcloudClient.login(identity, credential);
                        versionFound = true;
                        break;
                    }
                } catch (VCloudException e) {
                    LOG.info("VCloudClient - Cannot login using " + version);
                }
            }
            if (!versionFound) throw new IllegalStateException("Can't login to " + arg0);
            // Performing Certificate Validation
			if (trustStore != null) {
				System.setProperty("javax.net.ssl.trustStore", trustStore);
				System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
				vcloudClient.registerScheme("https", 443, CustomSSLSocketFactory.getInstance());

			} else {
                LOG.warn("Ignoring the Certificate Validation using FakeSSLSocketFactory");
                vcloudClient.registerScheme("https", 443, FakeSSLSocketFactory.getInstance());
			}
			return vcloudClient;
    	} catch (Exception e) {
    		throw Exceptions.propagate(e);
    	}
    }
    
    private ReferenceType generateReference(String href, String name, String type) {
        ReferenceType appliedOn = new ReferenceType();
        appliedOn.setHref(href);
        appliedOn.setName(name);
        appliedOn.setType(type);
        return appliedOn;
    }

    private GatewayNatRuleType generateGatewayNatRule(Protocol protocol, HostAndPort original,
            HostAndPort translated, ReferenceType interfaceRef) {
        GatewayNatRuleType gatewayNatRule = new GatewayNatRuleType();
        gatewayNatRule.setProtocol(protocol.toString());
        gatewayNatRule.setOriginalIp(original.getHostText());
        gatewayNatRule.setOriginalPort(""+original.getPort());
        gatewayNatRule.setTranslatedIp(translated.getHostText());
        gatewayNatRule.setTranslatedPort(""+translated.getPort());
        gatewayNatRule.setInterface(interfaceRef);
        return gatewayNatRule;
    }

    private NatRuleType generateDnatRule(boolean enabled, GatewayNatRuleType gatewayNatRule) {
        NatRuleType dnatRule = new NatRuleType();
        dnatRule.setIsEnabled(enabled);
        dnatRule.setRuleType("DNAT");
        dnatRule.setGatewayNatRule(gatewayNatRule);
        return dnatRule;
    }
}
