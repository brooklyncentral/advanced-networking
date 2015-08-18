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
package brooklyn.networking;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.brooklyn.util.net.Networking;

/**
 * Given several strings, determines which have the longest, and shorted, initial matching prefix.
 * Particularly useful as a poor-man's way to determine which IP's are likely to be the same subnet.
 */
public class NetworkMultiAddressUtils2 {

    private static final Logger log = LoggerFactory.getLogger(NetworkMultiAddressUtils2.class);
    
    public static boolean isAccessible(String host, int port, long timeoutMillis) {
        // from jclouds SocketOpen used by OpenSocketFinder
        long startTime = System.currentTimeMillis();
        long endTime = startTime + timeoutMillis;
        while (startTime <= endTime || timeoutMillis < 0) {
            startTime = System.currentTimeMillis();
            InetSocketAddress socketAddress = new InetSocketAddress(host, port);
            Socket socket = null;
            try {
                log.trace("testing socket {}", socketAddress);
                socket = new Socket();
                socket.setReuseAddress(false);
                socket.setSoLinger(false, 1);
                int runTimeout = (int)(endTime - startTime);
                if (runTimeout < 1000) runTimeout = 1000;
                if (runTimeout > 60000) runTimeout = 60000;
                socket.setSoTimeout(runTimeout);
                socket.connect(socketAddress, runTimeout);
                return true;
            } catch (IOException e) {
                // Ignore
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ioe) {
                        // Ignore
                    }
                }
            }
        }
        return false;
    }

    public static String getFirstAccessibleOnPort(int port, Iterable<String> addresses) {
        for (String addr: addresses) {
            if (isAccessible(addr, port, 500))
                return addr;
        }
        for (String addr: addresses) {
            if (isAccessible(addr, port, 5000))
                return addr;
        }
        return null;
    }

    public static boolean isResolveable(String vmHostname) {
        return Networking.resolve(vmHostname)!=null;
    }

}
