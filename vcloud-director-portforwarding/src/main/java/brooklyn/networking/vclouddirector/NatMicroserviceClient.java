package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.net.URI;

import org.apache.http.client.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.util.http.HttpTool;
import brooklyn.util.http.HttpToolResponse;
import brooklyn.util.net.Urls;
import brooklyn.util.text.Strings;

import com.google.common.annotations.Beta;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.escape.Escaper;
import com.google.common.net.HostAndPort;
import com.google.common.net.UrlEscapers;

/**
 * For adding/removing NAT rules to vcloud-director.
 * 
 * The threading model is that internally all access (to update DNAT rules) is synchronized.
 * The mutex to synchronize on can be passed in (see {@link NatMicroserviceClient.Builder#mutex(Object)}).
 * The intent is that the same mutex be passed in for everything accessing the same Edge Gateway.
 * It is extremely important to synchronize because adding NAT rule involves:
 * <ol>
 *   <li>download existing NAT rules;
 *   <li>add new rule to collection;
 *   <li>upload all NAT rules.
 * </ol>
 * Therefore if two threads execute concurrently we may not get both new NAT rules in the resulting uploaded set!
 */
@Beta
public class NatMicroserviceClient implements NatClient {

    private static final Logger LOG = LoggerFactory.getLogger(NatMicroserviceClient.class);
    
    private final String microserviceUri;
    private final String endpoint; // e.g. "https://p5v1-vcd.vchs.vmware.com:443";
    private final String vDC;
    private final String credential;
    private final String identity;


    public NatMicroserviceClient(String microserviceUri, JcloudsLocation loc) {
        this.microserviceUri = checkNotNull(microserviceUri, "microserviceUri");
        this.identity = checkNotNull(loc.getIdentity(), "identity");
        this.credential = checkNotNull(loc.getCredential(), "credential");
        this.vDC = loc.getRegion();
        
        checkArgument(identity.contains("@"), "identity %s does not contain vOrg, in location %s", identity, loc);
        String vOrg = identity.substring(identity.lastIndexOf("@") + 1);
        this.endpoint = NatDirectClient.transformEndpoint(loc.getEndpoint(), vOrg);
    }

    @Override
    public HostAndPort openPortForwarding(PortForwardingConfig args) {
        HttpClient client = HttpTool.httpClientBuilder()
                .uri(microserviceUri)
                .trustSelfSigned()
                .build();
        
        Escaper escaper = UrlEscapers.urlPathSegmentEscaper();
        URI uri = URI.create(Urls.mergePaths(microserviceUri, "/v1/nat"
                + "?endpoint="+escaper.escape(endpoint)
                + (Strings.isNonBlank(vDC) ? "&vdc="+escaper.escape(vDC) : "")
                + "&identity="+escaper.escape(identity)
                + "&credential="+escaper.escape(credential)
                + "&protocol=" + args.protocol
                + "&original=" + args.publicEndpoint
                + (args.publicPortRange == null ? "" : "&originalPortRange="+args.publicPortRange.toString())
                + "&translated=" + args.targetEndpoint));

        HttpToolResponse response = HttpTool.httpPut(client, uri, ImmutableMap.<String, String>of(), new byte[0]);
        if (response.getResponseCode() < 200 || response.getResponseCode() >= 300) {
            String msg = "Open NAT Rule failed for "+args+": "+response.getResponseCode()+"; "+response.getReasonPhrase()+": "+response.getContentAsString();
            LOG.info(msg+"; rethrowing");
            throw new RuntimeException(msg);
        }
        return HostAndPort.fromString(response.getContentAsString());
    }
    
    @Override
    public HostAndPort closePortForwarding(PortForwardingConfig args) {
        HttpClient client = HttpTool.httpClientBuilder()
                .uri(microserviceUri)
                .trustSelfSigned()
                .build();
        
        Escaper escaper = UrlEscapers.urlPathSegmentEscaper();
        URI uri = URI.create(Urls.mergePaths(microserviceUri, "/v1/nat"
                + "?endpoint="+escaper.escape(endpoint)
                + (Strings.isNonBlank(vDC) ? "&vdc="+escaper.escape(vDC) : "")
                + "&identity="+escaper.escape(identity)
                + "&credential="+escaper.escape(credential)
                + "&protocol=" + args.protocol
                + "&original=" + args.publicEndpoint
                + "&translated=" + args.targetEndpoint));

        HttpToolResponse response = HttpTool.httpDelete(client, uri, ImmutableMap.<String, String>of());
        if (response.getResponseCode() < 200 || response.getResponseCode() >= 300) {
            String msg = "Delete NAT Rule failed for "+args+": "+response.getResponseCode()+"; "+response.getReasonPhrase()+": "+response.getContentAsString();
            LOG.info(msg+"; rethrowing");
            throw new RuntimeException(msg);
        }
        return args.publicEndpoint;
    }
}
