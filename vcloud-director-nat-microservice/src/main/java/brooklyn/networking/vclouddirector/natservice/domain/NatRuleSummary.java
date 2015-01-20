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
package brooklyn.networking.vclouddirector.natservice.domain;

import java.net.URI;
import java.util.Map;

import org.codehaus.jackson.annotate.JsonProperty;

import com.google.api.client.repackaged.com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.vmware.vcloud.api.rest.schema.NatRuleType;

public class NatRuleSummary {

    private final String network;
    private final String protocol;
    private final String originalIp;
    private final String originalPort;
    private final String translatedIp;
    private final String translatedPort;
    private final String icmpSubType;
    private final Map<String, URI> links;

    public static NatRuleSummary from(NatRuleType input) {
        return new NatRuleSummary(
                input.getGatewayNatRule().getInterface().getName(),
                input.getGatewayNatRule().getProtocol(),
                input.getGatewayNatRule().getOriginalIp(),
                input.getGatewayNatRule().getOriginalPort(),
                input.getGatewayNatRule().getTranslatedIp(),
                input.getGatewayNatRule().getTranslatedPort(),
                input.getGatewayNatRule().getIcmpSubType(),
                ImmutableMap.<String, URI>of());
    }

    public NatRuleSummary(
            @JsonProperty("network") String network,
            @JsonProperty("protocol") String protocol,
            @JsonProperty("originalIp") String originalIp,
            @JsonProperty("originalPort") String originalPort,
            @JsonProperty("translatedIp") String translatedIp,
            @JsonProperty("translatedPort") String translatedPort,
            @JsonProperty("icmpSubType") String icmpSubType,
            @JsonProperty("links") Map<String, URI> links) {
        this.network = network;
        this.protocol = protocol;
        this.originalPort = originalPort;
        this.originalIp = originalIp;
        this.translatedIp = translatedIp;
        this.translatedPort = translatedPort;
        this.icmpSubType = icmpSubType;
        this.links = (links == null) ? ImmutableMap.<String, URI>of() : ImmutableMap.copyOf(links);
    }

    public String getNetwork() {
        return network;
    }
    
    public String getProtocol() {
        return protocol;
    }
    
    public String getOriginalIp() {
        return originalIp;
    }

    public String getOriginalPort() {
        return originalPort;
    }
    
    public String getTranslatedIp() {
        return translatedIp;
    }

    public String getTranslatedPort() {
        return translatedPort;
    }

    public String getIcmpSubType() {
        return icmpSubType;
    }
  
    public Map<String, URI> getLinks() {
        return links;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof NatRuleSummary)) return false;
        NatRuleSummary o = (NatRuleSummary) other;
        return Objects.equal(network, o.network) && Objects.equal(protocol, o.protocol) 
                && Objects.equal(originalIp, o.originalIp) && Objects.equal(originalPort, o.originalPort)
                && Objects.equal(translatedIp, o.translatedIp) && Objects.equal(translatedPort, o.translatedPort)
                && Objects.equal(icmpSubType, o.icmpSubType);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(network, protocol, originalIp, originalPort, translatedIp, translatedPort, icmpSubType);
    }

    @Override
    public String toString() {
        return "NatRuleSummary{" +
                "network=" + network +
                ", protocol=" + protocol +
                ", original=" + originalIp+":"+originalPort +
                ", translated=" + translatedIp+":"+translatedPort +
                ", icmpSubType=" + icmpSubType +
                '}';
    }
}
