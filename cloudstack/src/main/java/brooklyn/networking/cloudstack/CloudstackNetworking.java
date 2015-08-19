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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;

import org.jclouds.cloudstack.domain.Account;
import org.jclouds.cloudstack.domain.Network;
import org.jclouds.cloudstack.domain.Zone;
import org.jclouds.cloudstack.options.CreateNetworkOptions;
import org.jclouds.cloudstack.options.ListAccountsOptions;
import org.jclouds.cloudstack.options.ListNetworksOptions;
import org.jclouds.cloudstack.options.ListZonesOptions;

import org.apache.brooklyn.location.cloud.names.BasicCloudMachineNamer;
import org.apache.brooklyn.location.core.LocationConfigKeys;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.net.Cidr;

public class CloudstackNetworking {

    private static final String ISOLATED_NETWORK_OFFERING_NAME = "DefaultIsolatedNetworkOfferingWithSourceNatService";

    private static final Logger LOG = LoggerFactory.getLogger(CloudstackNetworking.class);

    protected final JcloudsLocation loc;
    protected final CloudstackNew40FeaturesClient client;

    public CloudstackNetworking(JcloudsLocation loc) {
        this.loc = checkNotNull(loc, "location");
        this.client = CloudstackNew40FeaturesClient.newInstance(loc);
    }

    public void close() {
        client.close();
    }

    public String findDomainIdForAccount(String accountName) {
        Set<Account> accounts = client.getAccountClient().listAccounts(ListAccountsOptions.Builder.name(accountName));
        Account account = Iterables.getOnlyElement(accounts, null);
        if (account == null) {
            throw new IllegalStateException("No account found in "+loc+"} with name "+accountName);
        }
        return account.getDomainId();
    }

    public Network findPrivateNetwork(String accountName, String domainId) {
        // note: listNetworks includes networks with "null" account
        Set<Network> networks = client.getNetworkClient().listNetworks(ListNetworksOptions.Builder.accountInDomain(accountName, domainId).isShared(false));
        for (Network network : networks) {
            if (accountName.equals(network.getAccount())) {
                LOG.debug("Found private network in {} for account {} in domain {}: {}", new Object[] {loc, accountName, domainId, network});
                return network;
            }
        }
        LOG.debug("No private network found in {} for account {} in domain {}: contenders {}", new Object[] {loc, accountName, domainId, networks});
        return null;
    }

    public Zone findAvailableZone(String domainId) {
        Set<Zone> zones = client.getZoneClient().listZones(ListZonesOptions.Builder.available(true));
        for (Zone zone : zones) {
            if (zone.getDomainId() == null || zone.getDomainId().equals(domainId)) {
                LOG.debug("Found available zone in {} for domain {}: {}", new Object[] {loc, domainId, zone});
                return zone;
            }
        }
        LOG.debug("No available zone for domain {}; zones: {}", domainId, zones);
        return null;
    }

    // create a private subnet, using the jclouds-cloudstack context
    public String createPrivateNetwork(String zoneId, Optional<Cidr> optionalSubnetCidr) {
        checkNotNull(loc, "location");
        checkArgument("cloudstack".equals(loc.getProvider()), "provider=%s", loc.getProvider());
        LOG.debug("Creating private network in {} (zone {})", loc, zoneId);

        Cidr subnetCidr = optionalSubnetCidr.isPresent() ? optionalSubnetCidr.get() : null;
        String nwOfferingId = client.getNetworkOfferingWithName(ISOLATED_NETWORK_OFFERING_NAME);

        CreateNetworkOptions options = new CreateNetworkOptions()
                .isShared(false);
        if (subnetCidr != null && subnetCidr.getLength() > 0) {
            // ignore empty cidrs
            // cloudstack -- subnet must have cidr length 22 or more
            while (subnetCidr.getLength() < 22) {
                LOG.info("Increasing subnet for "+loc+"("+optionalSubnetCidr+") to have length >= 22");
                subnetCidr = subnetCidr.subnet(10);
            }
            options.gateway(subnetCidr.addressAtOffset(1).getHostAddress())
                    .netmask(subnetCidr.netmask().getHostAddress());
        }
        String name = new BasicCloudMachineNamer().generateNewGroupId(new ConfigBag().configure(LocationConfigKeys.CALLER_CONTEXT, this));
        String displayText = name+" subnet";
        Network network = client.getNetworkClient().createNetworkInZone(zoneId, nwOfferingId, name, displayText, options);
        String networkId = network.getId();
        if (subnetCidr == null) {
            // TODO get subnet
            subnetCidr = new Cidr(network.getGateway()+"/"+24);
//          subnet = new Cidr(nw.getStartIP()+"/"+24);
            LOG.debug("Autodetected CIDR for {} as {} (assuming length 24)", network, optionalSubnetCidr);
        }

        LOG.debug("Created private network in {}: {}", loc, network);

        return networkId;
    }
}
