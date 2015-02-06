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
