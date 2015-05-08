/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.networking.vclouddirector.natservice.resources;

import java.util.List;

import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.location.basic.PortRanges;
import brooklyn.networking.vclouddirector.PortForwardingConfig;
import brooklyn.networking.vclouddirector.natservice.api.NatServiceApi;
import brooklyn.networking.vclouddirector.natservice.domain.NatRuleSummary;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.net.Protocol;
import brooklyn.util.text.Strings;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.net.HostAndPort;
import com.vmware.vcloud.api.rest.schema.NatRuleType;
import com.vmware.vcloud.sdk.VCloudException;

public class NatServiceResource extends AbstractRestResource implements NatServiceApi {

    private static final Logger LOG = LoggerFactory.getLogger(NatServiceResource.class);

    @Override
    public List<NatRuleSummary> list(String endpoint, String vDC, String identity, String credential) {
        try {
            LOG.info("listing nat rule on {} @ {} (vDC {})", new Object[]{identity, endpoint, vDC});
            List<NatRuleType> rules = dispatcher().getNatRules(endpoint, vDC, identity, credential);
            return FluentIterable
                    .from(rules)
                    .transform(new Function<NatRuleType, NatRuleSummary>() {
                        @Override
                        public NatRuleSummary apply(NatRuleType input) {
                            return NatRuleSummary.from(input);
                        }
                    })
                    .toList();
        } catch (VCloudException e) {
            throw Exceptions.propagate(e);
        }
    }

    @Override
    public String openPortForwarding(String endpoint, String vDC,
            String identity, String credential, String protocol,
            String original, String originalPortRange, String translated) {
        LOG.info("creating nat rule {} {} -> {}, on {} @ {} (vDC {})", new Object[]{protocol, original, translated, identity, endpoint, vDC});
        HostAndPort originalHostAndPort = HostAndPort.fromString(original);
        HostAndPort translatedHostAndPort = HostAndPort.fromString(translated);
        Preconditions.checkArgument(translatedHostAndPort.hasPort(), "translated %s must include port", translated);
        try {
            HostAndPort result = dispatcher().openPortForwarding(endpoint, vDC, identity, credential, new PortForwardingConfig()
                    .protocol(Protocol.valueOf(protocol.toUpperCase()))
                    .publicEndpoint(originalHostAndPort)
                    .publicPortRange(Strings.isBlank(originalPortRange) ? null : PortRanges.fromString(originalPortRange))
                    .targetEndpoint(translatedHostAndPort));
            
            return result.toString();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    @Override
    public Response closePortForwarding(String endpoint,
            String vDC, String identity, String credential,
            String protocol, String original, String translated) {
        LOG.info("deleting nat rule {} {} -> {}, on {} @ {} (vDC {})", new Object[]{protocol, original, translated, identity, endpoint, vDC});
        // TODO throw 404 if not found
        HostAndPort originalHostAndPort = HostAndPort.fromString(original);
        HostAndPort translatedHostAndPort = HostAndPort.fromString(translated);
        Preconditions.checkArgument(originalHostAndPort.hasPort(), "original %s must include port", original);
        Preconditions.checkArgument(translatedHostAndPort.hasPort(), "translated %s must include port", translated);
        try {
            dispatcher().closePortForwarding(endpoint, vDC, identity, credential, new PortForwardingConfig()
                    .protocol(Protocol.valueOf(protocol.toUpperCase()))
                    .publicEndpoint(originalHostAndPort)
                    .targetEndpoint(translatedHostAndPort));
            
            return Response.status(Response.Status.OK).build();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

}
