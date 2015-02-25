package brooklyn.networking.vclouddirector;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.http.conn.ssl.SSLSocketFactory;

import com.google.common.base.Throwables;

public class CustomSSLSocketFactory {

    private CustomSSLSocketFactory() {
    }

    public static SSLSocketFactory getInstance(String trustStore, String trustStorePassword) {
        try {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            FileInputStream keystoreStream = new FileInputStream(trustStore);
            KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            try {
                keystore.load(keystoreStream, trustStorePassword.toCharArray());
            } finally {
                keystoreStream.close();
            }
            trustManagerFactory.init(keystore);
            TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, null);
            SSLContext.setDefault(sslContext);
            return new SSLSocketFactory(sslContext, SSLSocketFactory.STRICT_HOSTNAME_VERIFIER);
        } catch (CertificateException e) {
            throw Throwables.propagate(e);
        } catch (NoSuchAlgorithmException e) {
            throw Throwables.propagate(e);
        } catch (KeyStoreException e) {
            throw Throwables.propagate(e);
        } catch (KeyManagementException e) {
            throw Throwables.propagate(e);
        } catch (FileNotFoundException e) {
            throw Throwables.propagate(e);
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }

}
