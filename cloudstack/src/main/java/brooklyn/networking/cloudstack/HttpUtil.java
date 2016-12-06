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
package brooklyn.networking.cloudstack;

import java.net.URI;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;

import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.http.HttpTool;
import org.apache.brooklyn.util.http.HttpToolResponse;
import org.apache.http.auth.Credentials;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.ssl.TrustStrategy;
import org.bouncycastle.util.io.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

/**
 * HTTP convenience static methods.
 * <p>
 * Uses the Apache {@link HttpClient} for connections.
 * 
 * @deprecated since 0.10.0; instead use {@link HttpTool}.
 */
@Deprecated
public class HttpUtil {

    public static final Logger LOG = LoggerFactory.getLogger(HttpUtil.class);

    public static HttpClient createHttpClient(URI uri, Optional<Credentials> credentials) {
        return HttpTool.httpClientBuilder()
                .uri(uri)
                .credential(credentials)
                .build();
    }

    public static HttpToolResponse invoke(org.jclouds.http.HttpRequest request) {
        HttpClient client = HttpTool.httpClientBuilder()
                .uri(request.getEndpoint())
                .build();
        String method = request.getMethod();
        try {
            if ("GET".equalsIgnoreCase(method)) {
                return HttpTool.httpGet(client, request.getEndpoint(), request.getHeaders());
            } else if ("POST".equalsIgnoreCase(method)) {
                return HttpTool.httpPost(client, request.getEndpoint(), request.getHeaders(), Streams.readAll(request.getPayload().openStream()));
            } else {
                // TODO being lazy!
                throw new UnsupportedOperationException("Unsupported method: "+method+" for "+request);
            }
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    public static HttpToolResponse httpGet(URI uri, Multimap<String,String> headers) {
        HttpClient client = HttpUtil.createHttpClient(uri, Optional.<Credentials>absent());
        return HttpTool.httpGet(client, uri, headers);
    }

    public static HttpToolResponse httpGet(HttpClient httpClient, URI uri, Map<String,String> headers) {
        return HttpTool.httpGet(httpClient, uri, Multimaps.forMap(headers));
    }

    public static HttpToolResponse httpGet(HttpClient httpClient, URI uri, Multimap<String,String> headers) {
        return HttpTool.httpGet(httpClient, uri, headers);
    }

    public static HttpToolResponse httpPost(HttpClient httpClient, URI uri, Map<String,String> headers, byte[] body) {
        return HttpTool.httpPost(httpClient, uri, Multimaps.forMap(headers), body);
    }

    public static HttpToolResponse httpPost(HttpClient httpClient, URI uri, Multimap<String,String> headers, byte[] body) {
        return HttpTool.httpPost(httpClient, uri, headers, body);
    }

    public static class TrustAllStrategy implements TrustStrategy {
        @Override
        public boolean isTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            return true;
        }
    }
}
