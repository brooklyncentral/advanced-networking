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
package brooklyn.networking.cloudstack.legacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.brooklyn.api.catalog.CatalogConfig;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.BasicConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.entity.core.AbstractApplication;
import org.apache.brooklyn.entity.core.StartableApplication;
import org.apache.brooklyn.location.access.PortForwardManager;
import org.apache.brooklyn.sensor.core.BasicAttributeSensor;
import org.apache.brooklyn.util.core.BrooklynNetworkUtils;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Networking;

public abstract class LegacyAbstractSubnetApp extends AbstractApplication implements StartableApplication {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyAbstractSubnetApp.class);

    @CatalogConfig(label="Enable VPC")
    public static final ConfigKey<Boolean> USE_VPC = VirtualPrivateCloud.USE_VPC;

    public static String VPC_CIDR_DEFAULT = "10.190.0.0/16";

    @CatalogConfig(label="App/VPC CIDR")
    public static final ConfigKey<Cidr> VPC_CIDR = ConfigKeys.newConfigKeyWithDefault(VirtualPrivateCloud.VPC_CIDR, null);

    @CatalogConfig(label="Enable subnet (implied if VPC)")
    public static final ConfigKey<Boolean> USE_SUBNET = LegacySubnetTier.USE_SUBNET;

    @CatalogConfig(label="Permitted Management Access CIDR", priority=-10)
    public static final ConfigKey<Cidr> MANAGEMENT_ACCESS_CIDR = ConfigKeys.newConfigKey(Cidr.class,
            LegacyJcloudsCloudstackSubnetLocation.MANAGEMENT_ACCESS_CIDR.getName(),
            LegacyJcloudsCloudstackSubnetLocation.MANAGEMENT_ACCESS_CIDR.getDescription(),
            new Cidr(BrooklynNetworkUtils.getLocalhostExternalIp()+"/32"));

    @CatalogConfig(label="Database in Separate Tier")
    public static final ConfigKey<Boolean> DB_IN_SEPARATE_SUBNET = new BasicConfigKey<Boolean>(
            Boolean.class, "app.db.newSubnet", "Run app in separate subnet [untested]", false);

    public static final AttributeSensor<String> VPC_ID = VirtualPrivateCloud.VPC_ID;

    public static final AttributeSensor<Integer> APPSERVERS_COUNT = new BasicAttributeSensor<Integer>(Integer.class,
            "appservers.count", "Number of app servers deployed");

    static {
        LOG.debug("Local addresses: "+Networking.getLocalAddresses());
        LOG.debug("Management access default CIDR: "+MANAGEMENT_ACCESS_CIDR.getDefaultValue());
    }

    protected <T> void setIfNotAlreadySet(ConfigKey<T> key, T value) {
        if (getConfigMap().getConfigRaw(key, true).isAbsent()) configure(key, value);
    }

    protected void applyDefaultConfig() {
        LOG.info("Constructing "+this);

        // default to using a subnet but not a VPC, if not otherwise specified --
        // means if localhost etc you have to specify _false_
        setIfNotAlreadySet(USE_VPC, false);
        setIfNotAlreadySet(USE_SUBNET, true);
        // FIXME not safe for persistence
        if (getConfigMap().getConfigRaw(LegacyJcloudsCloudstackSubnetLocation.PORT_FORWARDING_MANAGER, true).isAbsent()) {
            PortForwardManager pfm = (PortForwardManager) getManagementContext().getLocationRegistry().resolve("portForwardManager(scope=global)");
            configure(LegacyJcloudsCloudstackSubnetLocation.PORT_FORWARDING_MANAGER, pfm);
        }

        setIfNotAlreadySet(MANAGEMENT_ACCESS_CIDR, MANAGEMENT_ACCESS_CIDR.getDefaultValue());
        LOG.info("Management access will be granted to "+getConfig(MANAGEMENT_ACCESS_CIDR));

        if (getConfig(USE_VPC)) {
            setIfNotAlreadySet(VPC_CIDR, new Cidr(VPC_CIDR_DEFAULT));
        } else {
            // FIXME brooklyn - shame we have to do this, but brooklyn won't resolve it
            // if we call configure or setConfig
            setConfigEvenIfOwned(VPC_CIDR, (Cidr)null);
        }
    }

}
