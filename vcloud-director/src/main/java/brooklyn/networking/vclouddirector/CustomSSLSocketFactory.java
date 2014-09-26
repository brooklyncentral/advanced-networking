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

public class CustomSSLSocketFactory {

	private CustomSSLSocketFactory() {
	}

	public static SSLSocketFactory getInstance()
			throws NoSuchAlgorithmException, KeyStoreException,
			CertificateException, KeyManagementException, IOException {

		TrustManagerFactory trustManagerFactory = TrustManagerFactory
				.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
		try{
			String trustStore = System.getProperty("javax.net.ssl.trustStore");
			String trustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
			if(trustStore == null || trustStorePassword == null){
				throw new IOException("javax.net.ssl.trustStore/javax.net.ssl.trustStorePassword property - not set");
			}
			FileInputStream keystoreStream = new FileInputStream(trustStore);
			try{
				keystore = KeyStore.getInstance(KeyStore.getDefaultType());
				keystore.load(keystoreStream, trustStorePassword.toCharArray());
			} finally{
				keystoreStream.close();
			}
		} catch(FileNotFoundException e){
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		trustManagerFactory.init(keystore);
		TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
		SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(null, trustManagers, null);
		SSLContext.setDefault(sslContext);

		return new SSLSocketFactory(sslContext,
				SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
	}
}
