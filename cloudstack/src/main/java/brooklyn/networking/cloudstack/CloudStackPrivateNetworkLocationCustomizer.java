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

import static com.google.common.base.Preconditions.checkNotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;

import org.jclouds.cloudstack.compute.options.CloudStackTemplateOptions;
import org.jclouds.cloudstack.domain.Network;
import org.jclouds.cloudstack.domain.Zone;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.options.TemplateOptions;

import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.location.jclouds.BasicJcloudsLocationCustomizer;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.util.net.Cidr;

public class CloudStackPrivateNetworkLocationCustomizer extends BasicJcloudsLocationCustomizer {

    private static final Logger LOG = LoggerFactory.getLogger(CloudStackPrivateNetworkLocationCustomizer.class);

    public static final ConfigKey<String> ACCOUNT_NAME = ConfigKeys.newStringConfigKey("accountName", "the CloudStack account id");
    public static final ConfigKey<String> ZONE_ID = ConfigKeys.newStringConfigKey("zone", "the CloudStack zone (if not set, uses first available)");
    
    @Override
    public void customize(JcloudsLocation location, ComputeService computeService, TemplateOptions templateOptions) {
        if ("cloudstack".equals(location.getProvider())) {
            CloudStackTemplateOptions cloudstackTemplateOptions = (CloudStackTemplateOptions) templateOptions;
            if (cloudstackTemplateOptions.getNetworks().isEmpty()) {
                String networkId = getOrCreatePrivateNetwork(location);
                cloudstackTemplateOptions.networks(networkId);
            } else {
                LOG.info("Not adding CloudStack private network in {} because network already specified in template options: {}", 
                        location, cloudstackTemplateOptions.getNetworks());
            }
        }
    }
    
    private String getOrCreatePrivateNetwork(JcloudsLocation location) {
        CloudstackNetworking networking = new CloudstackNetworking(location);
        try {
            String accountName = checkNotNull(location.getConfig(ACCOUNT_NAME), "account");
            String domainId = networking.findDomainIdForAccount(accountName);
            String zoneId = location.getRegion();
            if (zoneId == null) {
                zoneId = location.getConfig(ZONE_ID);
            }
            if (zoneId == null) {
                Zone zone = networking.findAvailableZone(accountName);
                if (zone == null) {
                    throw new IllegalStateException("Not available zone found in "+location+", for account "+accountName);
                }
                zoneId = zone.getId();
            }
    
            String networkId;
            
            Network privateNetwork = networking.findPrivateNetwork(accountName, domainId);
            if (privateNetwork != null) {
                networkId = privateNetwork.getId();
                LOG.info("Using existing CloudStack private network in {} for {}: {}", new Object[] {location, accountName, networkId});
            } else {
                networkId = networking.createPrivateNetwork(zoneId, Optional.<Cidr>absent());
                LOG.info("Created new CloudStack private network in {} for {}: {}", new Object[] {location, accountName, networkId});
            }
    
            return networkId;
            
        } finally {
            networking.close();
        }
    }
}
