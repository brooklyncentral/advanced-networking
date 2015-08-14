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

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.bouncycastle.util.io.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.http.HttpToolResponse;

/**
 * HTTP convenience static methods.
 * <p>
 * Uses the Apache {@link HttpClient} for connections.
 */
public class HttpUtil {

    public static final Logger LOG = LoggerFactory.getLogger(HttpUtil.class);

    public static HttpClient createHttpClient(URI uri, Optional<Credentials> credentials) {
        final DefaultHttpClient httpClient = new DefaultHttpClient();

        // TODO if supplier returns null, we may wish to defer initialization until url available?
        if (uri != null && "https".equalsIgnoreCase(uri.getScheme())) {
            try {
                int port = (uri.getPort() >= 0) ? uri.getPort() : 443;
                SSLSocketFactory socketFactory = new SSLSocketFactory(
                        new TrustAllStrategy(), SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
                Scheme sch = new Scheme("https", port, socketFactory);
                httpClient.getConnectionManager().getSchemeRegistry().register(sch);
            } catch (Exception e) {
                LOG.warn("Error in HTTP Feed of {}, setting trust for uri {}", uri);
                throw Exceptions.propagate(e);
            }
        }

        // Set credentials
        if (uri != null && credentials.isPresent()) {
            String hostname = uri.getHost();
            int port = uri.getPort();
            httpClient.getCredentialsProvider().setCredentials(new AuthScope(hostname, port), credentials.get());
        }

        return httpClient;
    }

    public static HttpToolResponse invoke(org.jclouds.http.HttpRequest request) {
        HttpClient client = HttpUtil.createHttpClient(request.getEndpoint(), Optional.<Credentials>absent());
        String method = request.getMethod();
        try {
            if ("GET".equalsIgnoreCase(method)) {
                return HttpUtil.httpGet(client, request.getEndpoint(), request.getHeaders());
            } else if ("POST".equalsIgnoreCase(method)) {
                return HttpUtil.httpPost(client, request.getEndpoint(), request.getHeaders(), Streams.readAll(request.getPayload().openStream()));
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
        return HttpUtil.httpGet(client, uri, headers);
    }

    public static HttpToolResponse httpGet(HttpClient httpClient, URI uri, Map<String,String> headers) {
        return httpGet(httpClient, uri, Multimaps.forMap(headers));
    }

    public static HttpToolResponse httpGet(HttpClient httpClient, URI uri, Multimap<String,String> headers) {
        HttpGet httpGet = new HttpGet(uri);
        for (Map.Entry<String,String> entry : headers.entries()) {
            httpGet.addHeader(entry.getKey(), entry.getValue());
        }

        long startTime = System.currentTimeMillis();
        try {
            HttpResponse httpResponse = httpClient.execute(httpGet);
            try {
                return new HttpToolResponse(httpResponse, startTime);
            } finally {
                EntityUtils.consume(httpResponse.getEntity());
            }
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    public static HttpToolResponse httpPost(HttpClient httpClient, URI uri, Map<String,String> headers, byte[] body) {
        return httpPost(httpClient, uri, Multimaps.forMap(headers), body);
    }

    public static HttpToolResponse httpPost(HttpClient httpClient, URI uri, Multimap<String,String> headers, byte[] body) {
        HttpPost httpPost = new HttpPost(uri);
        for (Map.Entry<String,String> entry : headers.entries()) {
            httpPost.addHeader(entry.getKey(), entry.getValue());
        }
        if (body != null) {
            HttpEntity httpEntity = new ByteArrayEntity(body);
            httpPost.setEntity(httpEntity);
        }

        long startTime = System.currentTimeMillis();
        try {
            HttpResponse httpResponse = httpClient.execute(httpPost);

            try {
                return new HttpToolResponse(httpResponse, startTime);
            } finally {
                EntityUtils.consume(httpResponse.getEntity());
            }
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    public static class TrustAllStrategy implements TrustStrategy {
        @Override
        public boolean isTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            return true;
        }
    }
}
