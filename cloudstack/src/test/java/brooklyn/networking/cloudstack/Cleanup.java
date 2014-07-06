/*
 * Copyright 2013-2014 by Cloudsoft Corporation Limited
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

import java.util.Set;

import org.jclouds.cloudstack.domain.Network;
import org.jclouds.cloudstack.options.ListNetworksOptions;

import brooklyn.entity.basic.Entities;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.management.internal.LocalManagementContext;

import com.google.common.base.Joiner;

public class Cleanup {

    private final LocalManagementContext managementContext;
    private final JcloudsLocation loc;
    private final CloudstackNetworking networking;
    private final CloudstackNew40FeaturesClient client;

    private final String accountName;
    private final String domainId;

    public static void main(String[] args) {
        String accountName = args[0];
        Cleanup cleanup = new Cleanup(accountName);
        try {
            cleanup.listPrivateNetworks();
        } finally {
            cleanup.close();
        }
    }

    public Cleanup(String accountName) {
        managementContext = new LocalManagementContext();
        loc = (JcloudsLocation) managementContext.getLocationRegistry().resolve("citrix-cloudplatform");
        networking = new CloudstackNetworking(loc);
        client = CloudstackNew40FeaturesClient.newInstance(loc);

        this.accountName = accountName;
        this.domainId = networking.findDomainIdForAccount(accountName);
    }

    public void listPrivateNetworks() {
        Set<Network> networks = client.getNetworkClient().listNetworks(ListNetworksOptions.Builder.accountInDomain(accountName, domainId).isShared(false));
        System.out.println(Joiner.on("\n").join(networks));
    }

    public void close() {
        if (managementContext != null) Entities.destroyAll(managementContext);
        if (networking != null) networking.close();
        if (client != null) client.close();
    }
}
