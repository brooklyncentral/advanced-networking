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
package brooklyn.networking.common.subnet;

import java.util.List;

import com.google.common.annotations.Beta;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.location.access.PortForwardManager;
import org.apache.brooklyn.location.access.PortForwardManagerClient;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.HasNetworkAddresses;
import org.apache.brooklyn.util.net.Protocol;

/**
 * Delegating instance of {@link PortForwarder}, for persistence-safety.
 * <p>
 * @see brooklyn.location.access.PortForwardManagerClient
 */
@Beta
public class PortForwarderClient implements PortForwarder {

    protected final Supplier<PortForwarder> delegateSupplier;
    private transient volatile PortForwarder _delegate;
    
    protected PortForwarderClient(Supplier<PortForwarder> supplier) {
        this.delegateSupplier = supplier;
    }
    
    /** creates an instance, cf {@link PortForwardManagerClient#fromSupplier(Supplier)} */ 
    public static PortForwarder fromSupplier(Supplier<PortForwarder> supplier) {
        return new PortForwarderClient(supplier);
    }
    
    /** creates an instance, cf {@link PortForwardManagerClient#fromMethodOnEntity(Entity, String)} */ 
    public static PortForwarder fromMethodOnEntity(final Entity entity, final String getterMethodOnEntity) {
        Preconditions.checkNotNull(entity);
        Preconditions.checkNotNull(getterMethodOnEntity);
        return new PortForwarderClient(new Supplier<PortForwarder>() {
            @Override
            public PortForwarder get() {
                PortForwarder result;
                try {
                    result = (PortForwarder) entity.getClass().getMethod(getterMethodOnEntity).invoke(entity);
                } catch (Exception e) {
                    Exceptions.propagateIfFatal(e);
                    throw new IllegalStateException("Cannot invoke "+getterMethodOnEntity+" on "+entity+" ("+entity.getClass()+"): "+e, e);
                }
                if (result==null)
                    throw new IllegalStateException("No PortForwarder available via "+getterMethodOnEntity+" on "+entity+" (returned null)");
                return result;
            }
        });
    }
    
    /** creates an instance, cf {@link PortForwardManagerClient#fromConfigOnEntity(Entity, ConfigKey)} */ 
    public static PortForwarder fromConfigOnEntity(final Entity entity, final ConfigKey<PortForwarder> configOnEntity) {
        Preconditions.checkNotNull(entity);
        Preconditions.checkNotNull(configOnEntity);
        return new PortForwarderClient(new Supplier<PortForwarder>() {
            @Override
            public PortForwarder get() {
                PortForwarder result = (PortForwarder) entity.getConfig(configOnEntity);
                if (result==null)
                    throw new IllegalStateException("No PortForwarder available via "+configOnEntity+" on "+entity+" (returned null)");
                return result;
            }
        });
    }
    
    /** creates an instance, cf {@link PortForwardManagerClient#fromAttributeOnEntity(Entity, AttributeSensor)} */ 
    public static PortForwarder fromAttributeOnEntity(final Entity entity, final AttributeSensor<PortForwarder> attributeOnEntity) {
        Preconditions.checkNotNull(entity);
        Preconditions.checkNotNull(attributeOnEntity);
        return new PortForwarderClient(new Supplier<PortForwarder>() {
            @Override
            public PortForwarder get() {
                PortForwarder result = (PortForwarder) entity.getAttribute(attributeOnEntity);
                if (result==null)
                    throw new IllegalStateException("No PortForwarder available via "+attributeOnEntity+" on "+entity+" (returned null)");
                return result;
            }
        });
    }
    
    protected PortForwarder getDelegate() {
        if (_delegate==null) {
            _delegate = delegateSupplier.get();
        }
        return _delegate;
    }

    @Override
    public void injectManagementContext(ManagementContext managementContext) {
        getDelegate().injectManagementContext(managementContext);
    }
    
    @Override
    public void inject(Entity owner, List<Location> locations) {
        getDelegate().inject(owner, locations);
    }

    @Override
    public String openGateway() {
        return getDelegate().openGateway();
    }

    @Override
    public String openStaticNat(Entity serviceToOpen) {
        return getDelegate().openStaticNat(serviceToOpen);
    }

    @Override
    public void openFirewallPort(Entity entity, int port, Protocol protocol, Cidr accessingCidr) {
        getDelegate().openFirewallPort(entity, port, protocol, accessingCidr);
    }

    @Override
    public void openFirewallPortRange(Entity entity, PortRange portRange, Protocol protocol, Cidr accessingCidr) {
        getDelegate().openFirewallPortRange(entity, portRange, protocol, accessingCidr);
    }

    @Override
    public HostAndPort openPortForwarding(HasNetworkAddresses machine, int targetPort, Optional<Integer> optionalPublicPort,
        Protocol protocol, Cidr accessingCidr) {
        return getDelegate().openPortForwarding(machine, targetPort, optionalPublicPort, protocol, accessingCidr);
    }

    @Override
    public HostAndPort openPortForwarding(HostAndPort targetSide, Optional<Integer> optionalPublicPort, Protocol protocol,
        Cidr accessingCidr) {
        return getDelegate().openPortForwarding(targetSide, optionalPublicPort, protocol, accessingCidr);
    }

    @Override
    public boolean closePortForwarding(HostAndPort targetSide, HostAndPort publicSide, Protocol protocol) {
        return getDelegate().closePortForwarding(targetSide, publicSide, protocol);
    }

    @Override
    public boolean closePortForwarding(HasNetworkAddresses machine, int targetPort, HostAndPort publicSide, Protocol protocol) {
        return getDelegate().closePortForwarding(machine, targetPort, publicSide, protocol);
    }

    @Override
    public boolean isClient() {
        return true;
    }
    
    @Override
    public PortForwardManager getPortForwardManager() {
        return getDelegate().getPortForwardManager();
    }
    
}
