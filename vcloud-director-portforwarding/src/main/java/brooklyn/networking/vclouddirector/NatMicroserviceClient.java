/*
 * Copyright 2013-2015 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.net.URI;

import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.util.http.HttpTool;
import org.apache.brooklyn.util.http.HttpToolResponse;
import org.apache.brooklyn.util.net.Urls;
import org.apache.brooklyn.util.text.Strings;
import org.apache.http.client.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableMap;
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

    // TODO Parse output, rather than returning all as String?
    @Beta
    public String list() {
        HttpClient client = HttpTool.httpClientBuilder()
                .uri(microserviceUri)
                .trustSelfSigned()
                .build();
        
        Escaper escaper = UrlEscapers.urlPathSegmentEscaper();
        URI uri = URI.create(Urls.mergePaths(microserviceUri, "/v1/nat"
                + "?endpoint="+escaper.escape(endpoint)
                + (Strings.isNonBlank(vDC) ? "&vdc="+escaper.escape(vDC) : "")
                + "&identity="+escaper.escape(identity)
                + "&credential="+escaper.escape(credential)));

        if (LOG.isDebugEnabled()) LOG.debug("GET {}", uri.toString().replace(escaper.escape(credential), "xxxxxxxx"));
        
        HttpToolResponse response = HttpTool.httpGet(client, uri, ImmutableMap.<String, String>of());
        if (response.getResponseCode() < 200 || response.getResponseCode() >= 300) {
            String msg = "List NAT Rules failed for "+endpoint+": "+response.getResponseCode()+"; "+response.getReasonPhrase()+": "+response.getContentAsString();
            LOG.info(msg+"; rethrowing");
            throw new RuntimeException(msg);
        }
        return response.getContentAsString();
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
                + "&protocol=" + args.getProtocol()
                + "&original=" + args.getPublicEndpoint()
                + (args.getPublicPortRange() == null ? "" : "&originalPortRange="+args.getPublicPortRange().toString())
                + "&translated=" + args.getTargetEndpoint()));

        if (LOG.isDebugEnabled()) LOG.debug("PUT {}", uri.toString().replace(escaper.escape(credential), "xxxxxxxx"));
        
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
                + "&protocol=" + args.getProtocol()
                + "&original=" + args.getPublicEndpoint()
                + "&translated=" + args.getTargetEndpoint()));

        if (LOG.isDebugEnabled()) LOG.debug("DELETE {}", uri.toString().replace(escaper.escape(credential), "xxxxxxxx"));
        
        HttpToolResponse response = HttpTool.httpDelete(client, uri, ImmutableMap.<String, String>of());
        if (response.getResponseCode() < 200 || response.getResponseCode() >= 300) {
            String msg = "Delete NAT Rule failed for "+args+": "+response.getResponseCode()+"; "+response.getReasonPhrase()+": "+response.getContentAsString();
            LOG.info(msg+"; rethrowing");
            throw new RuntimeException(msg);
        }
        return args.getPublicEndpoint();
    }
}
