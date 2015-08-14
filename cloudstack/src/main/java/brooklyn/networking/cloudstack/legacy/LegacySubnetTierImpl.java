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

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.jclouds.cloudstack.domain.AsyncCreateResponse;
import org.jclouds.cloudstack.domain.FirewallRule;
import org.jclouds.cloudstack.domain.FirewallRule.Protocol;
import org.jclouds.cloudstack.domain.Network;
import org.jclouds.cloudstack.domain.PortForwardingRule;
import org.jclouds.cloudstack.domain.PublicIPAddress;
import org.jclouds.cloudstack.options.AssociateIPAddressOptions;
import org.jclouds.cloudstack.options.CreateFirewallRuleOptions;
import org.jclouds.cloudstack.options.CreateNetworkOptions;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.basic.EntityLocal;
import org.apache.brooklyn.api.event.AttributeSensor;
import org.apache.brooklyn.api.event.SensorEvent;
import org.apache.brooklyn.api.event.SensorEventListener;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.location.access.PortForwardManager;
import org.apache.brooklyn.location.access.PortMapping;
import org.apache.brooklyn.location.basic.AbstractLocation;
import org.apache.brooklyn.location.basic.LocationConfigKeys;
import org.apache.brooklyn.location.cloud.names.BasicCloudMachineNamer;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;

import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.trait.StartableMethods;
import brooklyn.networking.cloudstack.CloudstackNew40FeaturesClient;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.config.ConfigBag;
import brooklyn.util.flags.TypeCoercions;
import brooklyn.util.net.Cidr;
import brooklyn.util.text.Strings;

public class LegacySubnetTierImpl extends AbstractEntity implements LegacySubnetTier {

    private static final Logger log = LoggerFactory.getLogger(LegacySubnetTierImpl.class);

    protected CloudstackNew40FeaturesClient cloudstackClient;

    public boolean isSubnetEnabled() {
        return getConfig(USE_SUBNET) || isVpcEnabled();
    }

    public boolean isVpcEnabled() {
        return getConfig(VirtualPrivateCloud.USE_VPC);
    }

    public void start(Collection<? extends Location> locations) {
        addLocations(locations);
        if (isSubnetEnabled()) {
            Location l = Iterables.getOnlyElement(locations);
            if (l instanceof JcloudsLocation) {
                JcloudsLocation jl = (JcloudsLocation)l;
                String vpcId = getVpcId(jl);
                if ("cloudstack".equals(jl.getProvider())) {
                    log.info("Creating subnet tier "+this+
                            (isVpcEnabled() ? "" : " in VPC "+vpcId));
                    if (isVpcEnabled())
                        Preconditions.checkNotNull(vpcId, "vpcId");

                    cloudstackClient = CloudstackNew40FeaturesClient.newInstance(jl.getEndpoint(), jl.getIdentity(), jl.getCredential());
                    String zoneId = getZoneId(jl);

                    // create a private subnet, using the jclouds-cloudstack context
                    //                ((JcloudsLocation)location).getComputeService();
                    Cidr subnet = getConfig(SUBNET_CIDR);
                    if (isVpcEnabled() && subnet==null)
                        throw new IllegalStateException("Requires "+SUBNET_CIDR+" configured at "+this+" when using VPC");

                    String tierId;
                    boolean isLB = getConfig(SUBNET_IS_LOAD_BALANCER);
                    if (vpcId!=null) {
                        String nwOffering = cloudstackClient.getNetworkOfferingWithName(
                                isLB ? "DefaultIsolatedNetworkOfferingForVpcNetworks" :
                                "DefaultIsolatedNetworkOfferingForVpcNetworksNoLB");
                        // FIXME hardcoded identifiers above
                        tierId = cloudstackClient.createVpcTier("brooklyn-"+getId(), "brooklyn-"+getId()+" tier in VPC "+vpcId,
                                nwOffering,
                                zoneId, vpcId,
                                subnet.addressAtOffset(1).getHostAddress(),
                                subnet.netmask().getHostAddress());

                        // open all OUTBOUND -- ? is this needed ? for VPC
                        cloudstackClient.createNetworkAclAllEgress(tierId, new Cidr().toString());
                    } else {
                        String nwOffering = cloudstackClient.getNetworkOfferingWithName(
                                "DefaultIsolatedNetworkOfferingWithSourceNatService");
                        // FIXME hardcoded identifier above
                        CreateNetworkOptions options = new CreateNetworkOptions();
                        if (subnet!=null && subnet.getLength()>0) {
                            // ignore empty cidrs
                            // cloudstack -- subnet must have cidr length 22 or more
                            while (subnet.getLength()<22) {
                                log.info("Increasing subnet for "+this+"("+subnet+") to have length >=22");
                                subnet = subnet.subnet(10);
                            }
                            options.gateway(subnet.addressAtOffset(1).getHostAddress()).
                                netmask(subnet.netmask().getHostAddress());
                        }
                        String name = new BasicCloudMachineNamer().generateNewGroupId(new ConfigBag().configure(LocationConfigKeys.CALLER_CONTEXT, this));
                        Network nw = cloudstackClient.getNetworkClient().createNetworkInZone(zoneId, nwOffering,
                                name, name+" subnet",
                                options);
                        tierId = nw.getId();
                        if (subnet==null) {
                            // TODO get subnet
                            subnet = new Cidr(nw.getGateway()+"/"+24);
//                            subnet = new Cidr(nw.getStartIP()+"/"+24);
                            log.info("Autodetected CIDR for "+nw+" as "+subnet+" (assuming length 24)");
                        }
                        cloudstackClient.disableEgressFirewall(nw.getId());
                    }

                    // open configured INBOUND; this is mutexed with makeVisibleTo
                    Map<String, Cidr> acl = null;
                    synchronized (LegacySubnetTierImpl.this) {
                        acl = getAttribute(SUBNET_ACL);
                        setAttribute(NETWORK_ID, tierId);
                    }
                    if (acl!=null) openCidrAccess(acl.values());
                    // management inbound is done later, using this
                    Cidr portForwardingAclCidr = getConfig(LegacyJcloudsCloudstackSubnetLocation.MANAGEMENT_ACCESS_CIDR);

                    // create public IP
                    ensurePublicIpHostnameIdentifierForForwarding();
                    String publicIpId = getAttribute(PUBLIC_HOSTNAME_IP_ADDRESS_ID);
                    PortForwardManager pfw = getPortForwardManager();
                    pfw.recordPublicIpHostname(publicIpId, getAttribute(PUBLIC_HOSTNAME));

                    if (isLB) {
                        //create the load-balancer -- vpc only?
                        log.warn("Not creating load-balancer");
                    }

                    log.info("Created subnet "+tierId+" for "+this+", public IP is "+getAttribute(PUBLIC_HOSTNAME));

                    // now add the hooks to the location (or maybe create new location?)
                    // so that VM's created here get plumbed in to this subnet
                    JcloudsLocation jclSubnet = getManagementContext().getLocationManager().createLocation(
                        ConfigBag.newInstanceCopying(((JcloudsLocation) l).getLocalConfigBag())
                            .configure(AbstractLocation.PARENT_LOCATION, (JcloudsLocation)l)

                            // put these fields on the location so it has the info it needs to create the subnet
                            .configure(LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_ZONE_ID, zoneId)
                            .configure(LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_SUBNET_NETWORK_ID, tierId)
                            .configure(LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_SERVICE_NETWORK_ID, getConfig(SERVICE_NETWORK_ID))

                            // put this field on it so it has the info to see which address is in the subnet/tier
                            .configure(SUBNET_CIDR, subnet)

                            // no longer using
//                            .configure(JcloudsCloudstackSubnetLocation.SUBNET_ACCESS_MODE, JcloudsCloudstackSubnetLocation.SubnetAccessModes.EXTRA_SHARED_MANAGEMENT_NETWORK)
//                            .configure(JcloudsCloudstackSubnetLocation.CLOUDSTACK_SHARED_NETWORK_ID, sharedNetworkId)

                            // hardcode subnet + ip forward (see IP creation logic above;
                            // okay not to create IP unless it's needed for port forwarding)
                            .configure(LegacyJcloudsCloudstackSubnetLocation.PORT_FORWARDING_MANAGER, pfw)
                            .configure(LegacyJcloudsCloudstackSubnetLocation.MANAGEMENT_ACCESS_CIDR, portForwardingAclCidr)
                            .configure(LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_TIER_PUBLIC_IP_ID, publicIpId)

                            .getAllConfig(),
                        LegacyJcloudsCloudstackSubnetLocation.class);
                    locations = Arrays.<Location>asList(jclSubnet);
                    log.debug("Config of new location: {}", new Object[]{jclSubnet.getLocalConfigBag().getAllConfig()});
                }
            }
        }

        StartableMethods.start(this, locations);

        // TODO if we created a subnet above, configure the subnet so that the load-balancer is exposed
    }

    protected String getZoneId(Location jl) {
        String zoneId = jl.getConfig(LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_ZONE_ID);
        if (zoneId==null && jl instanceof JcloudsLocation) zoneId = ((JcloudsLocation)jl).getRegion();
        return Preconditions.checkNotNull(zoneId, "zoneId");
    }

    /** guaranteed not-null if using vpc, else guaranteed null */
    protected String getVpcId(Location l) {
        if (isVpcEnabled()) {
            return Preconditions.checkNotNull(l.getConfig(LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_VPC_ID),
                    "must set "+LegacyJcloudsCloudstackSubnetLocation.CLOUDSTACK_VPC_ID+" on location "+l+" to use VPC");
        }
        return null;
    }

    private synchronized PortForwardManager getPortForwardManager() {
        PortForwardManager pfw = getAttribute(SUBNET_SERVICE_PORT_FORWARDS);
        if (pfw==null) {
            pfw = getConfig(PORT_FORWARDING_MANAGER);
            if (pfw==null) {
                // FIXME not safe for persistence
                pfw = (PortForwardManager) getManagementContext().getLocationRegistry().resolve("portForwardManager(scope=global)");
                setConfigEvenIfOwned(PORT_FORWARDING_MANAGER, pfw);
            }
            setAttribute(SUBNET_SERVICE_PORT_FORWARDS, pfw);
        }
        return pfw;
    }

    @Override
    public void stop() {
        setAttribute(NETWORK_ID, null);
        StartableMethods.stop(this);

        // TODO delete network
    }

    @Override
    public void restart() {
        stop();
        start(getLocations());
    }

    @Override
    // TODO port ranges
    public void makeVisibleTo(LegacySubnetTier otherTier) {
        if (!isSubnetEnabled())
            return;

        AclUpdater acl = new AclUpdater();
        subscribe(otherTier, NETWORK_ID, acl);
        acl.apply(otherTier, otherTier.getAttribute(NETWORK_ID));
    }

    /** opens visibility to all ports to all elements in the other subnet */
    protected class AclUpdater implements SensorEventListener<String>{
        @Override
        public void onEvent(SensorEvent<String> event) {
            apply(event.getSource(), event.getValue());
        }
        public void apply(Entity source, String value) {
            Cidr newCidr = null, oldCidr = null;
            synchronized (LegacySubnetTierImpl.this) {
                Map<String, Cidr> acl = getAttribute(SUBNET_ACL);
                if (acl==null) acl = new LinkedHashMap<String, Cidr>();

                Cidr cidr = source.getConfig(SUBNET_CIDR);
                if (cidr==null) {
                    log.warn("No CIDR on subnet "+source+" wanting access to "+LegacySubnetTierImpl.this+"; ignoring");
                    return;
                }

                oldCidr = acl.remove(source.getId());
                if (value!=null) {
                    acl.put(source.getId(), cidr);
                    if (getAttribute(NETWORK_ID)!=null)
                        // only do this if we have a tier
                        // if we don't have a tier then the coming-online synch block will do it
                        newCidr = cidr;
                }

                setAttribute(SUBNET_ACL, acl);
            }

            if (oldCidr!=null) {
                // TODO remove the old one
                log.warn("Skipping removal of ACL rule for tier "+LegacySubnetTierImpl.this+" - not yet available");
            }
            if (newCidr!=null) {
                openCidrAccess(Arrays.asList(newCidr));
            }
        }
    }

    protected void openCidrAccess(Collection<Cidr> values) {
        JcloudsLocation jl = (JcloudsLocation) Iterables.getOnlyElement(getLocations(), null);
        if (jl==null) {
            log.info("Deferring new ACL rule for tier "+LegacySubnetTierImpl.this+" from "+values+" until "+this+" has a location");
            return;
        }

        String tierId = getAttribute(NETWORK_ID);
        if (tierId==null) {
            log.info("Deferring new ACL rule for tier "+LegacySubnetTierImpl.this+" from "+values+" until "+this+" has a tier");
            // will happen when comes online
            return;
        }

        cloudstackClient = CloudstackNew40FeaturesClient.newInstance(jl.getEndpoint(), jl.getIdentity(), jl.getCredential());
        if (isVpcEnabled()) {
            for (Cidr cidr: values) {
                cloudstackClient.createVpcNetworkAcl(tierId, "TCP", cidr.toString(), 1, 65535, null, null, "Ingress");
                cloudstackClient.createVpcNetworkAcl(tierId, "UDP", cidr.toString(), 1, 65535, null, null, "Ingress");
            }
        } else {
            log.warn("CIDR access to tiers not supported for non-VPC mode, from "+values+" to "+this);
        }

    }

    public void propagateSensorStringReplacingWithSubnetAddress(final Entity target, final AttributeSensor<String> sensor) {
        SensorPropagaterWithReplacement mapper = new SensorPropagaterWithReplacement(this, sensor, new Function<String,String>() {
            @Override
            public String apply(String input) {
                if (input==null) return null;
                String subnetHostname = target.getAttribute(SUBNET_HOSTNAME_SENSOR);
                log.debug("sensor mapper replacing address in "+this+"->"+sensor+", with "+subnetHostname+", where old address in "+target);
                String output = input;
                output = replaceIfNotNull(output, target.getAttribute(SoftwareProcess.HOSTNAME), subnetHostname);
                output = replaceIfNotNull(output, target.getAttribute(SoftwareProcess.ADDRESS), subnetHostname);
                return output;
            }
        });
        subscribe(target, sensor, mapper);
        mapper.apply( target.getAttribute(sensor) );
    }

    public static String replaceIfNotNull(String string, String searchFor, String replaceWith) {
        if (string==null || searchFor==null || replaceWith==null) return string;
        return Strings.replaceAll(string, searchFor, replaceWith);
    }

    public void transformSensorStringReplacingWithPublicAddressAndPort(
            final Entity sensorToMapFromSource, final AttributeSensor<String> sensorToMapFromPropagating,
            final Entity optionalSensorOfPortToMapFromSource, final AttributeSensor<Integer> optionalSensorOfPortToMapFrom,
            final Entity serviceHostPortToMapToEntity, final AttributeSensor<String> replacementTextSensor) {
        SensorPropagaterWithReplacement mapper = new SensorPropagaterWithReplacement(sensorToMapFromSource, sensorToMapFromPropagating, new Function<String,String>() {
            @Override
            public String apply(String sensorVal) {
                if (sensorVal==null) return null;
                String input = sensorToMapFromSource.getAttribute(sensorToMapFromPropagating);
                String replacement = serviceHostPortToMapToEntity.getAttribute(replacementTextSensor);
                log.debug("sensor mapper transforming address in "+sensorToMapFromSource+"->"+sensorToMapFromPropagating+", with "+replacement+" (old value is "+input+")");
                String suffix = "";
                if (optionalSensorOfPortToMapFromSource!=null || optionalSensorOfPortToMapFrom!=null) {
                    Integer port = null;
                    if (optionalSensorOfPortToMapFromSource!=null && optionalSensorOfPortToMapFrom!=null)
                        port = optionalSensorOfPortToMapFromSource.getAttribute(optionalSensorOfPortToMapFrom);
                    if (port==null) {
                        log.warn("no map-from port available for sensor mapper replacing addresses in "+serviceHostPortToMapToEntity+
                                " (listening on "+optionalSensorOfPortToMapFrom+")");
                        return input;
                    }
                    suffix = ":"+port;
                }
                String output = input;
                String localHostname = sensorToMapFromSource.getAttribute(SoftwareProcess.HOSTNAME);
                String localSubnetHostname = sensorToMapFromSource.getAttribute(SUBNET_HOSTNAME_SENSOR);
                String localAddress = sensorToMapFromSource.getAttribute(SoftwareProcess.ADDRESS);
                String privateHostname = sensorToMapFromSource.getAttribute(LegacySubnetTier.PRIVATE_HOSTNAME);
                output = replaceIfNotNull(output, localHostname+suffix, replacement);
                output = replaceIfNotNull(output, localSubnetHostname+suffix, replacement);
                output = replaceIfNotNull(output, localAddress+suffix, replacement);
                output = replaceIfNotNull(output, privateHostname+suffix, replacement);

                log.debug("sensor mapper transforming address in "+sensorToMapFromSource+"->"+sensorToMapFromPropagating+": "+
                        "input="+input+"; output="+output+
                        "; replacementSource="+serviceHostPortToMapToEntity+"->"+replacementTextSensor+
                        "; replacementText="+replacement+"; suffix="+suffix+
                        "; localHostname="+localHostname+"; localSubnetHostname="+localSubnetHostname+
                        "; localAddress="+localAddress+"; privateHostname="+privateHostname);
                return output;
            }
        });
        subscribe(sensorToMapFromSource, SoftwareProcess.HOSTNAME, mapper);
        subscribe(sensorToMapFromSource, sensorToMapFromPropagating, mapper);
        // assume hostname and port are set before the above subscription
        String newval = mapper.apply(sensorToMapFromSource.getAttribute(sensorToMapFromPropagating));
        if (newval != null) {
            setAttributeIfChanged(sensorToMapFromSource, sensorToMapFromPropagating, newval);
        }
    }

    public static class SensorPropagaterWithReplacement implements SensorEventListener<String> {
        private Function<String, String> function;
        private Entity owner;
        private AttributeSensor<String> sensor;
        public SensorPropagaterWithReplacement(Entity owner, AttributeSensor<String> sensor, Function<String, String> function) {
            this.owner = owner;
            this.sensor = sensor;
            this.function = function;
        }
        @Override
        public void onEvent(SensorEvent<String> event) {
            String v = Strings.toString(event.getValue());
            String v2 = apply(v);
            setAttributeIfChanged(owner, sensor, v2);
        }
        public String apply(String value) {
            return function.apply(value);
        }
    }

    protected abstract class SubnetFirewallRule {
        protected final Cidr accessingCidr;
        protected final Entity whereToAdvertisePublicServiceEndpoint;
        protected final AttributeSensor<String> sensorAdvertisingEndpointAttribute;

        protected SubnetFirewallRule(Cidr accessingCidr,
                Entity whereToAdvertisePublicServiceEndpoint, AttributeSensor<String> sensorAdvertisingEndpointAttribute) {
            this.accessingCidr = accessingCidr;
            this.whereToAdvertisePublicServiceEndpoint = whereToAdvertisePublicServiceEndpoint;
            this.sensorAdvertisingEndpointAttribute = sensorAdvertisingEndpointAttribute;
        }

        protected abstract void subscribe(Entity serviceToOpen, SensorEventListener<Object> listener);

        protected abstract boolean isReady(Entity serviceToOpen);

        protected abstract void open(String publicIp, Entity serviceToOpen);
    }

    @Override
    public void openPublicIp(EntityAndAttribute<String> whereToAdvertiseHostname) {
        PublicIPUpdater updater = new PublicIPUpdater(whereToAdvertiseHostname);
        subscribe(this, NETWORK_ID, updater);
    }

    @Override
    public void openStaticNat(Entity serviceToOpen, AttributeSensor<String> sensorAdvertisingHostname) {
        StaticNatUpdater updater = new StaticNatUpdater(serviceToOpen, sensorAdvertisingHostname);
        subscribe(serviceToOpen, Attributes.HOSTNAME, updater);
    }

    @Override
    public void openFirewallPort(EntityAndAttribute<String> publicIp, int port, FirewallRule.Protocol protocol, Cidr accessingCidr) {
        openFirewallPortRange(publicIp, port, port, protocol, accessingCidr);
    }

    @Override
    public void openFirewallPortRange(EntityAndAttribute<String> publicIp, int lowerBoundPort, int upperBoundPort, FirewallRule.Protocol protocol, Cidr accessingCidr) {
        SimpleFirewallUpdater updater = new SimpleFirewallUpdater(publicIp, lowerBoundPort, upperBoundPort, protocol, accessingCidr);
        subscribe(publicIp.getEntity(), publicIp.getAttribute(), updater);
        updater.apply(publicIp.getEntity(), publicIp.getValue());
    }

    @Override
    public void openFirewallPortAndAdvertise(EntityAndAttribute<String> publicIp, EntityAndAttribute<?> portSensor,
            Integer optionalPublicPort, Protocol protocol, Cidr accessingCidr, EntityAndAttribute<String> whereToAdvertiseEndpoint) {
        FirewallUpdater2 updater = new FirewallUpdater2(publicIp, portSensor, optionalPublicPort, protocol, accessingCidr,
                whereToAdvertiseEndpoint);
        subscribe(publicIp.getEntity(), publicIp.getAttribute(), updater);
        subscribe(portSensor.getEntity(), portSensor.getAttribute(), updater);
        updater.apply(publicIp.getEntity(), publicIp.getValue());
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void openFirewallPortAndAssign(Entity serviceToForward, AttributeSensor<?> servicePortSensor, Integer optionalPublicPort, Cidr accessingCidr,
            Entity whereToAdvertisePublicServiceEndpoint, AttributeSensor<String> sensorAdvertisingHostnameAndPort) {
        FirewallUpdater updater = new FirewallUpdater(serviceToForward, servicePortSensor, optionalPublicPort, accessingCidr,
                whereToAdvertisePublicServiceEndpoint, sensorAdvertisingHostnameAndPort);
        subscribe(serviceToForward, servicePortSensor, updater);
        // either of these may be null when the above comes through
        // FIXME Should PUBLIC_HOSTNAME be subscribed to `this`, rather than serviceToForward
        subscribe(serviceToForward, Attributes.HOSTNAME, updater);
        subscribe(serviceToForward, PUBLIC_HOSTNAME, updater);
    }

    protected class StaticNatUpdater implements SensorEventListener<Object> {
        final Entity serviceToOpen;
        final AttributeSensor<String> sensorAdvertisingHostname;

        public StaticNatUpdater(Entity serviceToOpen, AttributeSensor<String> sensorAdvertisingHostname) {
            this.serviceToOpen = serviceToOpen;
            this.sensorAdvertisingHostname = sensorAdvertisingHostname;
        }
        @Override
        public void onEvent(SensorEvent<Object> event) {
            apply(event.getSource(), event.getValue());
        }
        public void apply(Entity source, Object valueIgnored) {
            Location targetVm = Iterables.getOnlyElement(((EntityLocal)serviceToOpen).getLocations(), null);
            if (targetVm==null) {
                log.warn("Skipping port forward rule for "+serviceToOpen+" because it does not have a location");
                return;
            }

            if (isSubnetEnabled()) {
                PublicIPAddress ip = systemCreatePublicIpHostname();
                ((EntityLocal)serviceToOpen).setAttribute(sensorAdvertisingHostname, ip.getIPAddress());

                boolean success = systemEnableStaticNat(ip.getId(), targetVm);

                if (success) {
                    log.debug("Enabled static NAT via to {} via {} (VM {})", new Object[] {serviceToOpen, ip, targetVm});
                }
            }
        }
    }

    protected class PublicIPUpdater implements SensorEventListener<Object> {
        final EntityAndAttribute<String> whereToAdvertiseHostname;

        public PublicIPUpdater(EntityAndAttribute<String> whereToAdvertiseHostname) {
            this.whereToAdvertiseHostname = whereToAdvertiseHostname;
        }
        @Override
        public void onEvent(SensorEvent<Object> event) {
            apply(event.getSource(), event.getValue());
        }
        public void apply(Entity source, Object valueIgnored) {
            Location targetVm = Iterables.getOnlyElement(getLocations(), null);
            if (targetVm==null) {
                log.warn("Skipping adding public IP to "+this+" because it does not have a location");
                return;
            }

            PublicIPAddress ip = systemCreatePublicIpHostname();
            setAttributeIfChanged(whereToAdvertiseHostname, ip.getIPAddress());
        }
    }


    /**
     * Opens a firewall rule to a given port range on an entity/VM.
     * Does *NOT* set up port-forwarding.
     */
    protected class SimpleFirewallUpdater implements SensorEventListener<Object> {
        private final EntityAndAttribute<String> publicIp;
        final int lowerBoundPort;
        final int upperBoundPort;
        final FirewallRule.Protocol protocol;
        final Cidr cidr;

        public SimpleFirewallUpdater(EntityAndAttribute<String> publicIp, int lowerBoundPort, int upperBoundPort, FirewallRule.Protocol protocol, Cidr cidr) {
            this.publicIp = publicIp;
            this.lowerBoundPort = lowerBoundPort;
            this.upperBoundPort = upperBoundPort;
            this.protocol = protocol;
            this.cidr = cidr;
        }
        @Override
        public void onEvent(SensorEvent<Object> event) {
            apply(event.getSource(), event.getValue());
        }
        public void apply(Entity source, Object valueIgnored) {
            if (isSubnetEnabled()) {
                String ip = (String) publicIp.getValue();
                if (ip == null) {
                    log.warn("Skipping firewall rule for "+publicIp.getEntity()+"->"+publicIp.getAttribute()+" (ports "+lowerBoundPort+"-"+upperBoundPort+") because it does not have an IP");
                    return;
                }

                String ipId = retrievePublicIpId(ip);
                if (ipId == null) {
                    log.error("Skipping firewall rule for "+ip+" because reverse-lookup of ip-id failed");
                    return;
                }

                boolean success = systemOpenFirewall(ipId, cidr, lowerBoundPort, upperBoundPort, protocol);

                if (success) {
                    log.debug("Firewall opened: "+ip+":"+lowerBoundPort+"-"+upperBoundPort);
                }
            }
        }
    }

    /**
     * FIXME Duplication of FirewallUpdater! But in this code, we use the publicIp provided rather than a
     * generic public ip associated with the subnet.
     */
    protected class FirewallUpdater2 implements SensorEventListener<Object> {
        protected final EntityAndAttribute<String> publicIp;
        protected final EntityAndAttribute<?> portSensor;
        protected final Integer optionalPublicPort;
        protected final FirewallRule.Protocol protocol;
        protected final Cidr cidr;
        protected final EntityAndAttribute<String> whereToAdvertiseEndpoint;

        public FirewallUpdater2(EntityAndAttribute<String> publicIp, EntityAndAttribute<?> portSensor,
                Integer optionalPublicPort, Protocol protocol, Cidr cidr, EntityAndAttribute<String> whereToAdvertiseEndpoint) {
            this.publicIp = publicIp;
            this.portSensor = portSensor;
            this.optionalPublicPort = optionalPublicPort;
            this.protocol = protocol;
            this.cidr = cidr;
            this.whereToAdvertiseEndpoint = whereToAdvertiseEndpoint;
        }
        @Override
        public void onEvent(SensorEvent<Object> event) {
            apply(event.getSource(), event.getValue());
        }
        public void apply(Entity source, Object valueIgnored) {
            Location targetVm = Iterables.getOnlyElement(portSensor.getEntity().getLocations(), null);
            if (targetVm==null) {
                log.warn("Skipping port forward rule for "+portSensor.getEntity()+" because it does not have a location");
                return;
            }

            String newFrontEndpoint = null;
            PortMapping oldMapping = null;

            if (isSubnetEnabled()) {
                String ip = (String) publicIp.getValue();
                if (ip == null) {
                    log.warn("Skipping firewall rule for "+publicIp+" because it does not have an IP");
                    return;
                }

                String ipId = retrievePublicIpId(ip);
                if (ipId == null) {
                    log.error("Skipping firewall rule for "+publicIp+" because reverse-lookup of ip-id failed");
                    return;
                }

                Integer privateServicePort = TypeCoercions.coerce(portSensor.getValue(), Integer.class);
                if (privateServicePort==null) {
                    log.warn("Skipping port forward rule for "+publicIp+" because it does not advertise a port on "+portSensor);
                    return;
                }

                PortForwardManager pfw = getPortForwardManager();
                oldMapping = pfw.getPortMappingWithPrivateSide(targetVm, privateServicePort);

                if (oldMapping!=null) {
                    // TODO don't remove the entry if it isn't changing
                    pfw.forgetPortMapping(oldMapping);
                    // TODO actually remove the old one from the subnet
                    log.warn("Skipping removal of port forwarding rule for tier "+LegacySubnetTierImpl.this+", rule "+oldMapping);
                }

                int publicPort;
                if (optionalPublicPort==null) {
                    // TODO ensure cidr, publicIpId correct
                    if (oldMapping == null) {
                        publicPort = getPortForwardManager().acquirePublicPort(ipId);
                    } else {
                        publicPort = oldMapping.getPublicPort();
                    }
                } else {
                    publicPort = optionalPublicPort;
                    // TODO ensure cidr, publicIpId correct
                    if (oldMapping == null || oldMapping.getPublicPort() != publicPort) {
                        getPortForwardManager().acquirePublicPortExplicit(ipId, publicPort);
                    }
                }

                systemCreatePortForwarding(ipId, cidr, publicPort, targetVm, privateServicePort);

                newFrontEndpoint = ip+":"+publicPort;

                log.debug("Port forward details: "+portSensor.getEntity()+":"+portSensor.getAttribute().getName()+" = "+newFrontEndpoint);
            } else {
                // pass through
                newFrontEndpoint = portSensor.getEntity().getAttribute(Attributes.HOSTNAME)+":"+portSensor.getAttribute();
            }

            // TODO avoid yucky null: check
            if (newFrontEndpoint!=null && !newFrontEndpoint.startsWith("null:")) {
                whereToAdvertiseEndpoint.setValue(newFrontEndpoint);
            } else if (oldMapping!=null) {
                whereToAdvertiseEndpoint.setValue(null);
            }
        }
    }

    /**
     * Opens a firewall rule to a given port on an entity/VM, and optionally updates sensors.
     * Sets up port-forwarding for this entity/VM's ip:port, via the CloudStack public ip.
     */
    protected class FirewallUpdater implements SensorEventListener<Object>{
        final Entity serviceToForward;
        final AttributeSensor<?> servicePortSensor;
        final Integer optionalPublicPort;
        final Cidr cidr;
        final Entity whereToAdvertisePublicServiceEndpoint;
        final AttributeSensor<String> sensorAdvertisingHostnameAndPort;
        public FirewallUpdater(Entity serviceToForward, AttributeSensor<?> servicePortSensor, Integer optionalPublicPort, Cidr cidr,
                Entity whereToAdvertisePublicServiceEndpoint, AttributeSensor<String> sensorAdvertisingHostnameAndPort) {
            this.serviceToForward = serviceToForward;
            this.servicePortSensor = servicePortSensor;
            this.optionalPublicPort = optionalPublicPort;
            this.cidr = cidr;
            this.whereToAdvertisePublicServiceEndpoint = whereToAdvertisePublicServiceEndpoint;
            this.sensorAdvertisingHostnameAndPort = sensorAdvertisingHostnameAndPort;
        }
        @Override
        public void onEvent(SensorEvent<Object> event) {
            apply(event.getSource(), event.getValue());
        }
        public void apply(Entity source, Object valueIgnored) {
            Location targetVm = Iterables.getOnlyElement(((EntityLocal)serviceToForward).getLocations(), null);
            if (targetVm==null) {
                log.warn("Skipping port forward rule for "+serviceToForward+" because it does not have a location");
                return;
            }
            Integer privateServicePort = TypeCoercions.coerce(serviceToForward.getAttribute(servicePortSensor), Integer.class);
            if (privateServicePort==null) {
                log.warn("Skipping port forward rule for "+serviceToForward+" "+servicePortSensor.getName()+" because it does not advertise a port");
                return;
            }
            String newFrontEndpoint = null;
            PortMapping oldMapping = null;

            if (isSubnetEnabled()) {
                PortForwardManager pfw = getPortForwardManager();
                oldMapping = pfw.getPortMappingWithPrivateSide(targetVm, privateServicePort);

                if (oldMapping!=null) {
                    // TODO don't remove the entry if it isn't changing
                    pfw.forgetPortMapping(oldMapping);
                    // TODO actually remove the old one from the subnet
                    log.warn("Skipping removal of port forwarding rule for tier "+LegacySubnetTierImpl.this+", rule "+oldMapping);
                }
                if (valueIgnored!=null) {
                    Integer publicPort = getPortForwarding(pfw, cidr, targetVm, privateServicePort, optionalPublicPort);
                    if (publicPort==null) return;
                    newFrontEndpoint = getAttribute(PUBLIC_HOSTNAME)+":"+publicPort;
                }

                log.debug("Port forward details: "+serviceToForward.getId()+":"+servicePortSensor.getName()+" = "+newFrontEndpoint);
            } else {
                // pass through
                newFrontEndpoint = serviceToForward.getAttribute(Attributes.HOSTNAME)+":"+privateServicePort;
            }

            if (whereToAdvertisePublicServiceEndpoint != null) {
                // TODO avoid yucky null: check
                if (newFrontEndpoint!=null && !newFrontEndpoint.startsWith("null:")) {
                    setAttributeIfChanged(whereToAdvertisePublicServiceEndpoint, sensorAdvertisingHostnameAndPort, newFrontEndpoint);
                } else if (oldMapping!=null) {
                    setAttributeIfChanged(whereToAdvertisePublicServiceEndpoint, sensorAdvertisingHostnameAndPort, null);
                }
            }
        }
    }

    protected Integer getPortForwarding(PortForwardManager mgr, Cidr cidr, Location targetVm, int privatePort, Integer optionalPublicPort) {
        try {
            PortMapping mapping = mgr.getPortMappingWithPrivateSide(targetVm, privatePort);
            String publicIpId = ensurePublicIpHostnameIdentifierForForwarding();
            int publicPort;
            if (optionalPublicPort==null) {
                // TODO ensure cidr, publicIpId correct
                if (mapping!=null) return mapping.getPublicPort();
                publicPort = getPortForwardManager().acquirePublicPort(publicIpId);
            } else {
                publicPort = optionalPublicPort;
                // TODO ensure cidr, publicIpId correct
                if (mapping!=null && mapping.getPublicPort()==publicPort)
                    return mapping.getPublicPort();
                getPortForwardManager().acquirePublicPortExplicit(publicIpId, publicPort);
            }
            getPortForwardManager().associate(publicIpId, publicPort, targetVm, privatePort);

            systemCreatePortForwarding(publicIpId, cidr, publicPort, targetVm, privatePort);

            return publicPort;
        } catch (Exception e) {
            log.warn("Unable to get port forwarding for "+cidr+" to "+targetVm+":"+privatePort+" (in "+this+")", e);
            return null;
        }
    }

    protected synchronized String ensurePublicIpHostnameIdentifierForForwarding() {
        String ipid = getAttribute(PUBLIC_HOSTNAME_IP_ADDRESS_ID);
        if (ipid!=null) return ipid;
        try {
            systemCreatePublicIpHostnameForForwarding();
        } catch (Exception e) {
            log.error("Unable to get public IP for "+this+": "+e);
        }
        ipid = getAttribute(PUBLIC_HOSTNAME_IP_ADDRESS_ID);
        if (ipid==null) {
            log.warn("No public IP ID available for "+this);
        }
        return ipid;
    }

    // TODO remove duplication with JclousCloudstackSubnetLocation

    protected void systemCreatePublicIpHostnameForForwarding() {
        PublicIPAddress ip = systemCreatePublicIpHostname();
        setAttribute(PUBLIC_HOSTNAME, ip.getIPAddress());
        setAttribute(PUBLIC_HOSTNAME_IP_ADDRESS_ID, ip.getId());
    }

    protected PublicIPAddress systemCreatePublicIpHostname() {
        PublicIPAddress ip;
        Location l = Iterables.getOnlyElement(getLocations());
        String vpcId = getVpcId(l);
        if (vpcId==null) {
            String zoneId = getZoneId(l);
            String networkId = getAttribute(NETWORK_ID);
            AsyncCreateResponse response = cloudstackClient.getCloudstackGlobalClient().getAddressApi().associateIPAddressInZone(zoneId,
                    AssociateIPAddressOptions.Builder.networkId(networkId));
            cloudstackClient.waitForJobSuccess(response.getJobId());
            ip = cloudstackClient.getCloudstackGlobalClient().getAddressApi().getPublicIPAddress(response.getId());
        } else {
            ip = cloudstackClient.createIpAddressForVpc(vpcId);
        }

        synchronized (this) {
            Map<String, String> ips = getAttribute(PUBLIC_HOSTNAME_IP_IDS);
            if (ips == null) {
                ips = ImmutableMap.of();
            }
            Map<String,String> newips = MutableMap.<String,String>builder().putAll(ips).put(ip.getIPAddress(), ip.getId()).build();

            setAttribute(PUBLIC_HOSTNAME_IP_IDS, newips);
        }

        return ip;
    }

    protected boolean systemCreatePortForwarding(String publicIpId, Cidr cidr, int publicPort, Location targetVm, int privatePort) {
        String targetVmId = targetVm.getConfig(LegacyJcloudsCloudstackSubnetLocation.VM_IDENTIFIER);
        if (targetVmId==null) {
            log.warn("Skipping creation of port forward rule for "+targetVm+" port "+privatePort+" because location does not have an identifier set");
            // throw?
            return false;
        }

        try {
            String tierId = getAttribute(NETWORK_ID);
            String jobId = null;
            boolean success = true;
            if (isVpcEnabled()) {
                // network ID needed
                jobId = cloudstackClient.createPortForwardRuleForVpc(
                        tierId,
                        publicIpId, PortForwardingRule.Protocol.TCP,
                        publicPort, targetVmId, privatePort);
                success &= cloudstackClient.waitForJobsSuccess(Arrays.asList(jobId));
                cloudstackClient.createVpcNetworkAcl(tierId, "TCP", cidr.toString(), publicPort, publicPort, null, null, "INGRESS");
                // private doesn't need to be opened

            } else {
                jobId = cloudstackClient.createPortForwardRuleForVm(publicIpId, PortForwardingRule.Protocol.TCP,
                        publicPort, targetVmId, privatePort);
                success &= cloudstackClient.waitForJobsSuccess(Arrays.asList(jobId));

                success &= systemOpenFirewall(publicIpId, cidr, publicPort, publicPort, FirewallRule.Protocol.TCP);
                // private doesn't need to be opened
            }

            if (!success) {
                log.error("Failed creating port forwarding rule on "+this+" to "+targetVmId);
                // it might already be created, so don't crash and burn too hard!
                return false;
            }
        } catch (Exception e) {
            log.error("Failed creating port forwarding rule on "+this+" to "+targetVmId+": "+e);
            // it might already be created, so don't crash and burn too hard!
            return false;
        }

        return true;
    }

    protected boolean systemOpenFirewall(String publicIpId, Cidr cidr, int lowerBoundPort, int upperBoundPort, FirewallRule.Protocol protocol) {
        try {
            boolean success = true;
            CreateFirewallRuleOptions options = CreateFirewallRuleOptions.Builder.
                    startPort(lowerBoundPort).endPort(upperBoundPort).CIDRs(ImmutableSet.of(cidr.toString()));
            AsyncCreateResponse job = cloudstackClient.getCloudstackGlobalClient().getFirewallApi().createFirewallRuleForIpAndProtocol(
                    publicIpId, protocol, options);
            success &= cloudstackClient.waitForJobsSuccess(Arrays.asList(job.getJobId()));

            if (!success) {
                log.error("Failed creating firewall rule on "+this+" to "+publicIpId+":"+lowerBoundPort+"-"+upperBoundPort);
                // it might already be created, so don't crash and burn too hard!
                return false;
            }
        } catch (Exception e) {
            log.error("Failed creating firewall rule on "+this+" to "+publicIpId+":"+lowerBoundPort+"-"+upperBoundPort);
            // it might already be created, so don't crash and burn too hard!
            return false;
        }

        return true;
    }

    protected boolean systemEnableStaticNat(String publicIpId, Location targetVm) {
        String targetVmId = targetVm.getConfig(LegacyJcloudsCloudstackSubnetLocation.VM_IDENTIFIER);
        if (targetVmId==null) {
            log.warn("Skipping enabling of static nat for "+targetVm+" because location does not have an identifier set");
            // throw?
            return false;
        }

        try {
            cloudstackClient.getNATClient().enableStaticNATForVirtualMachine(targetVmId, publicIpId);

        } catch (Exception e) {
            log.error("Failed creating firewall rule on "+this+" to "+targetVmId+": "+e);
            // it might already be created, so don't crash and burn too hard!
            return false;
        }

        return true;
    }

    private String retrievePublicIpId(String publicIp) {
        Map<String,String> idsByIp = getAttribute(PUBLIC_HOSTNAME_IP_IDS);
        return (idsByIp == null) ? null : idsByIp.get(publicIp);
    }

    private static <T> void setAttributeIfChanged(EntityAndAttribute<T> entityAndAttribute, T val) {
        setAttributeIfChanged(entityAndAttribute.getEntity(), entityAndAttribute.getAttribute(), val);
    }

    private static <T> void setAttributeIfChanged(Entity entity, AttributeSensor<T> attribute, T val) {
        Object oldval = entity.getAttribute(attribute);
        if (!Objects.equal(oldval, val)) {
            ((EntityLocal)entity).setAttribute(attribute, val);
        }
    }
}
