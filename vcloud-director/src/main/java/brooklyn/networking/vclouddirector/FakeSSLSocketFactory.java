/*
 * *******************************************************
 * Copyright VMware, Inc. 2010-2013.  All Rights Reserved.
 * *******************************************************
 *
 * DISCLAIMER. THIS PROGRAM IS PROVIDED TO YOU "AS IS" WITHOUT
 * WARRANTIES OR CONDITIONS # OF ANY KIND, WHETHER ORAL OR WRITTEN,
 * EXPRESS OR IMPLIED. THE AUTHOR SPECIFICALLY # DISCLAIMS ANY IMPLIED
 * WARRANTIES OR CONDITIONS OF MERCHANTABILITY, SATISFACTORY # QUALITY,
 * NON-INFRINGEMENT AND FITNESS FOR A PARTICULAR PURPOSE.
 */
package brooklyn.networking.vclouddirector;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;

public class FakeSSLSocketFactory {

    private FakeSSLSocketFactory() {
    }

    public static SSLSocketFactory getInstance() throws KeyManagementException,
            UnrecoverableKeyException, NoSuchAlgorithmException,
            KeyStoreException {
        return new SSLSocketFactory(new TrustStrategy() {
            public boolean isTrusted(final X509Certificate[] chain,
                    final String auth) throws CertificateException {
                return true;
            }

        }, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
    }
}
