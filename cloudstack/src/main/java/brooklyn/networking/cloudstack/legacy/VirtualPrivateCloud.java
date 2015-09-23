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

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.BasicConfigKey;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.net.Cidr;

import brooklyn.networking.cloudstack.CloudstackNew40FeaturesClient;

public class VirtualPrivateCloud {

    private static final Logger log = LoggerFactory.getLogger(VirtualPrivateCloud.class);
    
    public static final ConfigKey<Boolean> USE_VPC = new BasicConfigKey<Boolean>(Boolean.class, 
            "bt.topology.useVpc", "Whether to use VPC's", false);

    public static final AttributeSensor<String> VPC_ID = new BasicAttributeSensor<String>(String.class, "vpc.id");
    public static final ConfigKey<Cidr> VPC_CIDR = new BasicConfigKey<Cidr>(Cidr.class, 
            "vpc.cidr", "CIDR to use for the VPC", new Cidr("10.0.0.0/16"));
    // sensor not the cleanest way to pass in VPC usage details!
    public static final AttributeSensor<Integer> VPC_TIER_COUNT = new BasicAttributeSensor<Integer>(
            Integer.class, "vpc.tier.count", "number of VPC tiers");

    public static Location createVpc(Location l, Entity owner) {
        if (owner.getConfig(USE_VPC)) {
            if (l instanceof JcloudsLocation) {
                JcloudsLocation jl = (JcloudsLocation)l;
                if ("cloudstack".equals(jl.getProvider())) {
                    // create a VPC, and set the ID in the entity
                    CloudstackNew40FeaturesClient cloudstackClient = CloudstackNew40FeaturesClient.newInstance(jl.getEndpoint(), jl.getIdentity(), jl.getCredential());

                    // FIXME hardcoded zone
                    String zoneId = cloudstackClient.findZoneMatchingRegex("CSB Advanced.*").getId();
                    String vpcId;
                    
                    // allow re-use, for speed
                    Tasks.setBlockingDetails("Checking VPC's");
                    vpcId = cloudstackClient.findVpcIdWithCidr(owner.getConfig(VPC_CIDR).toString());
                    if (vpcId!=null) {
                        cloudstackClient.deleteIpsAtVpc(vpcId);
                        log.info("Reusing VPC "+vpcId+" for "+owner);
                    } else {
                        log.info("Creating VPC on start of "+owner);
                        Tasks.setBlockingDetails("Creating VPC");
                        vpcId = cloudstackClient.createVpc(
                            owner.getConfig(VPC_CIDR).toString(), 
                            "Brooklyn VPC for "+owner.getDisplayName()+" ("+owner.getId()+")", 
                            "brooklyn-"+owner.getId(), 
                            cloudstackClient.getFirstVpcOfferingId(),
                            zoneId);
                        log.info("Created VPC "+vpcId+" on start of "+owner);
                    }
                    Tasks.setBlockingDetails(null);
                    owner.sensors().set(VPC_ID, vpcId);
                    return jl.newSubLocation(MutableMap.of(
                            LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_VPC_ID, vpcId,
                            LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_ZONE_ID, zoneId
                        ));
                }
            }
        }
        return l;
    }

    public static void deleteVpc(Location l, Entity owner) {
        if (owner.getConfig(USE_VPC)) {
            if (l instanceof JcloudsLocation) {
                JcloudsLocation jl = (JcloudsLocation)l;
                if ("cloudstack".equals(jl.getProvider())) {
                    // create a VPC, and set the ID in the entity
                    CloudstackNew40FeaturesClient cloudstackClient = CloudstackNew40FeaturesClient.newInstance(jl.getEndpoint(), jl.getIdentity(), jl.getCredential());

                    log.info("Deleting VPC on stop of "+owner);
                    cloudstackClient.deleteVpc(owner.getAttribute(VPC_ID));
                    owner.sensors().set(VPC_ID, null);
                }
            }
        }
    }

    public static void publishSubnetHostnames(final Entity root) {
        // FIXME this subscribed to everything, but not sure what was meant
//        ((AbstractEntity)root).subscribe(null, Attributes.HOSTNAME, new SensorEventListener<String>() {
//            @Override
//            public void onEvent(SensorEvent<String> event) {
//                if (event.getValue()==null) {
//                    log.debug("VPC detected "+event.getSource()+" has lost its hostname");
//                    // going away
//                    ((EntityLocal)event.getSource()).setAttribute(SubnetTier.SUBNET_HOSTNAME_SENSOR, null);
//                } else if (event.getSource().getLocations().isEmpty()) {
//                    log.debug("VPC detected "+event.getSource()+" (with hostname "+event.getValue()+") has no locations");
//                } else {
//                    try {
//                        Location l = Iterables.getOnlyElement(event.getSource().getLocations());
//                        String subnetHostname = (String) l.getConfig(JcloudsCloudstackSubnetLocation.SUBNET_HOSTNAME_CONFIG);
//                        if (subnetHostname!=null) {
//                            log.info("VPC detected "+event.getSource()+" has new mgmt/shared network hostname "+event.getValue()+" and subnet hostname "+subnetHostname);
//                            ((EntityLocal)event.getSource()).setAttribute(SubnetTier.SUBNET_HOSTNAME_SENSOR, subnetHostname);
//                        } else {
//                            log.debug("VPC detected "+event.getSource()+" has new hostname "+event.getValue()+" but no subnet hostname "+subnetHostname);
//                        }
//                    } catch (Exception e) {
//                        log.warn("Problem getting hostname on "+event+": "+e);
//                    }
//                }
//            }
//        });
    }
    
}
