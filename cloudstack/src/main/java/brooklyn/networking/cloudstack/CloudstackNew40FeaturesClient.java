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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.jclouds.Constants;
import org.jclouds.ContextBuilder;
import org.jclouds.cloudstack.CloudStackContext;
import org.jclouds.cloudstack.CloudStackGlobalApi;
import org.jclouds.cloudstack.domain.AsyncJob;
import org.jclouds.cloudstack.domain.AsyncJob.Status;
import org.jclouds.cloudstack.domain.Network;
import org.jclouds.cloudstack.domain.NetworkOffering;
import org.jclouds.cloudstack.domain.PortForwardingRule.Protocol;
import org.jclouds.cloudstack.domain.PublicIPAddress;
import org.jclouds.cloudstack.domain.VirtualMachine;
import org.jclouds.cloudstack.domain.Zone;
import org.jclouds.cloudstack.features.AsyncJobApi;
import org.jclouds.cloudstack.features.GlobalAccountApi;
import org.jclouds.cloudstack.features.GlobalHostApi;
import org.jclouds.cloudstack.features.GlobalOfferingApi;
import org.jclouds.cloudstack.features.GlobalPodApi;
import org.jclouds.cloudstack.features.GlobalVlanApi;
import org.jclouds.cloudstack.features.GlobalZoneApi;
import org.jclouds.cloudstack.features.LoadBalancerApi;
import org.jclouds.cloudstack.features.NATApi;
import org.jclouds.cloudstack.features.NetworkApi;
import org.jclouds.cloudstack.features.VirtualMachineApi;
import org.jclouds.cloudstack.filters.QuerySigner;
import org.jclouds.cloudstack.options.ListNetworkOfferingsOptions;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.HttpResponse;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.guava.Maybe;
import brooklyn.util.http.HttpToolResponse;
import brooklyn.util.time.Time;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.inject.Module;

public class CloudstackNew40FeaturesClient {

    private static final Logger LOG = LoggerFactory.getLogger(CloudstackNew40FeaturesClient.class);

    private final String endpoint;
    private final String apiKey;
    //context knows it and gives us the signer; included for completeness only
    @SuppressWarnings("unused") private final String secretKey;

    private final CloudStackContext context;

    public static CloudstackNew40FeaturesClient newInstance(JcloudsLocation loc) {
        return newInstance(loc.getConfig(JcloudsLocation.CLOUD_ENDPOINT), loc.getIdentity(), loc.getCredential());
    }

    public static CloudstackNew40FeaturesClient newInstance(String endpoint, String apiKey, String secretKey) {
        Properties overrides = new Properties();
        overrides.setProperty(Constants.PROPERTY_TRUST_ALL_CERTS, "true");
        overrides.setProperty(Constants.PROPERTY_RELAX_HOSTNAME, "true");

        ContextBuilder builder = ContextBuilder
            .newBuilder("cloudstack")
            .endpoint(endpoint)
            .apiVersion("3.0.5")
            .credentials(apiKey, secretKey)
            .modules(ImmutableSet.<Module>builder()
                            .add(new SLF4JLoggingModule())
                            .build()
            )
            .overrides(overrides);

        CloudStackContext context = builder.buildView(CloudStackContext.class);
        return new CloudstackNew40FeaturesClient(endpoint, apiKey, secretKey, context);
    }

    public CloudstackNew40FeaturesClient(String endpoint, String apiKey, String secretKey, CloudStackContext context) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.endpoint = endpoint;
        this.context = context;
    }

    public void close() {
        context.close();
    }

    public CloudStackGlobalApi getCloudstackGlobalClient() {
        return context.getGlobalApi();
    }

    public LoadBalancerApi getLoadBalancerClient() {
        return getCloudstackGlobalClient().getLoadBalancerApi();
    }

    public GlobalAccountApi getAccountClient() {
        return getCloudstackGlobalClient().getAccountApi();
    }

    public AsyncJobApi getAsyncJobClient() {
        return getCloudstackGlobalClient().getAsyncJobApi();
    }

    public QuerySigner getQuerySigner() {
        return context.utils().injector().getInstance(QuerySigner.class);
    }

    public NetworkApi getNetworkClient() {
        return getCloudstackGlobalClient().getNetworkApi();
    }

    public VirtualMachineApi getVirtualMachineClient() {
        return getCloudstackGlobalClient().getVirtualMachineApi();
    }

    public GlobalOfferingApi getOfferingClient() {
        return getCloudstackGlobalClient().getOfferingApi();
    }

    public GlobalVlanApi getVlanClient() {
        return getCloudstackGlobalClient().getVlanClient();
    }

    public GlobalHostApi getHostClient() {
        return getCloudstackGlobalClient().getHostClient();
    }

    public NATApi getNATClient() {
        return getCloudstackGlobalClient().getNATApi();
    }

    public GlobalPodApi getPodClient() {
        return getCloudstackGlobalClient().getPodClient();
    }

    public GlobalZoneApi getZoneClient() {
        return getCloudstackGlobalClient().getZoneApi();
    }

    public List<String> findVpcIdsNameMatchingRegex(String regex) throws InterruptedException {
        List<String> result = new ArrayList<String>();

        JsonArray jr = listVpcsJson();
        if (jr==null) return result;

        Iterator<JsonElement> jvii = jr.iterator();

        while (jvii.hasNext()) {
            JsonObject jvo = jvii.next().getAsJsonObject();
            String name = jvo.get("name").getAsString();
            if (name!=null && name.matches(regex))
                result.add(jvo.get("id").getAsString());
        }
        LOG.debug("VPC's matching {}: {}, ", regex, result);

        return result;
    }

    public String findVpcIdWithCidr(String cidr) {
        JsonArray jr = listVpcsJson();
        if (jr==null) return null;
        Iterator<JsonElement> jvii = jr.iterator();
        List<String> cidrs = new ArrayList<String>();
        while (jvii.hasNext()) {
            JsonObject jvo = jvii.next().getAsJsonObject();
            String cidrV = jvo.get("cidr").getAsString();
            if (cidrV!=null && cidrV.equals(cidr)) {
                String vpcId = jvo.get("id").getAsString();
                LOG.debug("found vpcId {} matching CIDR {}", vpcId, cidr);
                return vpcId;
            }
            cidrs.add(cidrV);
        }
        LOG.debug("Found VPC's with CIDR's {} but not {}", cidrs, cidr);
        return null;
    }

    protected JsonArray listVpcsJson() {
        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "listVPCs");

        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        HttpRequest request = HttpRequest.builder()
            .method("GET")
            .endpoint(this.endpoint)
            .addQueryParams(params)
            .addHeader("Accept", "application/json")
            .build();

        request = getQuerySigner().filter(request);

        HttpToolResponse response = HttpUtil.invoke(request);

        JsonElement jr = json(response);
        LOG.debug(pretty(jr));

        JsonElement vpcs = jr.getAsJsonObject().get("listvpcsresponse").getAsJsonObject().get("vpc");
        return vpcs==null ? null : vpcs.getAsJsonArray();
    }

    public String createVpc(String cidr, String displayText, String name, String vpcOfferingId, String zoneId) {
        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "createVPC");
        params.put("cidr", cidr);
        params.put("displayText", displayText);
        params.put("name", name);
        params.put("vpcOfferingId", vpcOfferingId);
        params.put("zoneId", zoneId);

        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        HttpRequest request = HttpRequest.builder()
            .method("GET")
            .endpoint(this.endpoint)
            .addQueryParams(params)
            .addHeader("Accept", "application/json")
            .build();

        request = getQuerySigner().filter(request);

        HttpToolResponse response = HttpUtil.invoke(request);
        // todo: handle non-2xx response

        try {
            return waitForJobCompletion(response);
        } catch (InterruptedException e) {
            throw Exceptions.propagate(e);
        }
    }

    public String deleteVpc(String vpcId) {
        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "deleteVPC");
        params.put("id", vpcId);

        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        HttpRequest request = HttpRequest.builder()
            .method("GET")
            .endpoint(this.endpoint)
            .addQueryParams(params)
            .addHeader("Accept", "application/json")
            .build();

        request = getQuerySigner().filter(request);

        HttpToolResponse response = HttpUtil.invoke(request);
        // todo: handle non-2xx response

        try {
            return waitForJobCompletion(response);
        } catch (InterruptedException e) {
            throw Exceptions.propagate(e);
        }
    }
    /** gets the ID of the thing whose job we were waiting on, if applicable */
    protected String waitForJobCompletion(HttpToolResponse response) throws InterruptedException {
        // FIXME response.getMessage(), to do something like httpUrlConnection.getResponseMessage()
        return waitForJobCompletion(response.getResponseCode(), new ByteArrayInputStream(response.getContent()), "HTTP response");
    }

    protected String waitForJobCompletion(HttpResponse response) throws InterruptedException, IOException {
        return waitForJobCompletion(response.getStatusCode(), response.getPayload().openStream(), response.getMessage());
    }

    protected String waitForJobCompletion(int statusCode, InputStream payload, String message) throws InterruptedException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new RuntimeException("Error: " + message);
        }

        JsonElement jr = json(payload);
        LOG.debug(pretty(jr));

        JsonObject jobfields = jr.getAsJsonObject().entrySet().iterator().next().getValue().getAsJsonObject();
        JsonElement responseIdJson = jobfields.get("id");
        String responseId = responseIdJson!=null ? responseIdJson.getAsString() : null;
        String jobId = jobfields.get("jobid").getAsString();

        do {
            AsyncJob<Object> job = getAsyncJobClient().getAsyncJob(jobId);
            LOG.debug("waiting: "+job);
            if (job.hasFailed()) throw new IllegalStateException("Failed job: "+job);
            if (job.hasSucceed()) {
                Status status = job.getStatus();
                if (Status.FAILED.equals(status)) throw new IllegalStateException("Failed job: "+job);
                if (Status.SUCCEEDED.equals(status)) return responseId;
            }
            Thread.sleep(1000);
        } while (true);
    }

    public static JsonElement json(HttpToolResponse response) {
        return json(new ByteArrayInputStream(response.getContent()));
    }

    public static JsonElement json(HttpResponse response) throws IOException {
        return json(response.getPayload().openStream());
    }

    public static JsonElement json(InputStream is) {
        JsonParser parser = new JsonParser();
        JsonReader reader = null;
        try {
            reader = new JsonReader(new InputStreamReader(is, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        JsonElement el = parser.parse(reader);
        return el;
    }

    public static String pretty(InputStream is) {
        return pretty(json(is));
    }

    public static String pretty(JsonElement js) {
        return gson().toJson(js);
    }

    protected static Gson gson() {
        return new GsonBuilder()
            .setPrettyPrinting()
            .create();
    }

    private Set<Zone> zones = null;

    public Zone findZoneMatchingName(String name) {
        if (zones==null) zones = getZoneClient().listZones();
        for (Zone z: zones) if (name.equals(z.getName())) return z;
        return null;
    }

    public Zone findZoneMatchingRegex(String regex) {
        if (zones==null) zones = getZoneClient().listZones();
        for (Zone z: zones) if (z.getName()!=null && z.getName().matches(regex)) return z;
        return null;
    }

    public String getFirstVpcOfferingId() {
        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "listVPCOfferings");
        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        HttpRequest request = HttpRequest.builder()
            .method("GET")
            .endpoint(this.endpoint)
            .addQueryParams(params)
            .addHeader("Accept", "application/json")
            .build();

        request = getQuerySigner().filter(request);

        HttpToolResponse response = HttpUtil.invoke(request);
        JsonElement offers = json(response);
        LOG.debug("LIST VPC OFFERS\n"+pretty(offers));

        String id = offers.getAsJsonObject().get("listvpcofferingsresponse").
            getAsJsonObject().get("vpcoffering").getAsJsonArray().
            get(0).getAsJsonObject().get("id").getAsString();
        LOG.debug("  using first VPC offering ID: "+id);
        return id;
    }

    public String createVpcTier(String name, String displayText,
            String networkOfferingId,
            String zoneId, String vpcId,
            String gateway, String netmask) {

        //vpcid
        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "createNetwork");

        params.put("displayText", displayText);
        params.put("name", name);
        params.put("networkofferingid", networkOfferingId);
        params.put("zoneid", zoneId);
        params.put("vpcid", vpcId);
        params.put("gateway", gateway);
        params.put("netmask", netmask);

        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        LOG.debug("createVpcTier GET "+params);

        HttpRequest request = HttpRequest.builder()
            .method("GET")
            .endpoint(this.endpoint)
            .addQueryParams(params)
            .addHeader("Accept", "application/json")
            .build();

        request = getQuerySigner().filter(request);

        HttpToolResponse response = HttpUtil.invoke(request);
        // TODO does non-2xx response need to be handled separately ?

        JsonElement jr = json(response);
        LOG.debug("createVpcTier GOT "+jr);

        // seems this is created immediately
        return jr.getAsJsonObject().get("createnetworkresponse")
                .getAsJsonObject().get("network")
                .getAsJsonObject().get("id")
                .getAsString();
    }

    public Network findNetworkNameMatchingRegex(String regex) {
        Set<Network> networks = getNetworkClient().listNetworks();
        LOG.debug("NETWORKS: ");
        for (Network nw: networks) {
            LOG.debug("  "+nw);
            if (nw.getName().matches(regex)) {
                LOG.debug("  ^^^");
                return nw;
            }
        }
        return null;
    }

    public void deleteVpcsWhereNameMatchesRegex(String regex) {
        // as needed, delete them:
        int delCount = 0;
        List<String> vpcIds = null;
        try {
            vpcIds = findVpcIdsNameMatchingRegex(regex);
        } catch (InterruptedException e1) {
            throw Exceptions.propagate(e1);
        }
        for (String vpcId: vpcIds) {
            try {
                LOG.debug("deleting VPC "+vpcId);
                deleteVpc(vpcId);
                delCount++;
            } catch (Exception e) {
                LOG.info("not allowed to delete "+vpcId+": "+e);
            }
        }
        if (delCount>0) LOG.info("deleted "+delCount+" vpc's");
    }

    /** returns the count of matching items which could not be deleted */
    public int deleteVmsWhereNameMatchesRegex(String regex, boolean waitForExpunged) {
        // as needed, delete them:
        CloudstackNew40FeaturesClient client = this;
        int delCount = 0, nonDelCount = 0;
        Set<VirtualMachine> vms = client.getVirtualMachineClient().listVirtualMachines();
        List<String> jobs = new ArrayList<String>();
        for (VirtualMachine vm: vms) {
            try {
                if (vm.getName().matches(regex)) {
                    LOG.debug("deleting "+vm);
                    String job = client.getVirtualMachineClient().destroyVirtualMachine(vm.getId());
                    LOG.debug("deleting "+vm+" - job "+job);
                    if (job!=null) jobs.add(job);
                    delCount++;
                } else {
                    LOG.debug("skipping deletion of (non-matched) "+vm);
                }
            } catch (Exception e) {
                nonDelCount++;
                LOG.info("not allowed to delete "+vm+": "+e);
            }
        }
        waitForJobs(jobs);
        if (delCount>0) LOG.info("deleted "+delCount+" VM's");
        if (nonDelCount==0 && waitForExpunged) {
            int loops = 0;
            while (true) {
                boolean match = false;
                vms = client.getVirtualMachineClient().listVirtualMachines();
                for (VirtualMachine vm: vms) {
                    match |= vm.getName().matches(regex);
                }
                if (!match) {
                    if (loops>0)
                        LOG.info("VM's now all expunged");
                    break;
                }
                if (loops==0)
                    LOG.info("waiting for VM's to be expunged");
                else
                    LOG.debug("still waiting for VM's to be expunged");
                loops++;
                Time.sleep(2000);
            }
        }
        return nonDelCount;
    }

    /** returns false if any failed or were unknown */
    public boolean waitForJobsSuccess(Iterable<String> jobs) {
        List<AsyncJob<Object>> result = waitForJobsDone(jobs);
        List<AsyncJob<Object>> failures = Lists.newArrayList();
        for (AsyncJob<Object> r : result) {
            if (!r.hasSucceed()) {
                failures.add(r);
            }
        }
        if (failures.isEmpty()) {
            return true;
        } else {
            throw new IllegalStateException("job(s) failed: "+failures);
        }
    }

    /** returns false if any failed or were unknown */
    public boolean waitForJobs(Iterable<String> jobs) {
        List<AsyncJob<Object>> result = waitForJobsDone(jobs);
        boolean failure = false;
        for (AsyncJob<Object> r : result) {
            if (!r.hasSucceed()) {
                failure = true;
                LOG.warn("job failed: "+r);
            }
        }
        return !failure;
    }

    /** returns all jobs */
    public List<AsyncJob<Object>> waitForJobsDone(Iterable<String> jobs) {
        List<AsyncJob<Object>> result = Lists.newArrayList();
        for (String job: jobs) {
            AsyncJob<Object> j = waitForJob(job);
            LOG.debug("job completed with status: "+j);
            result.add(j);
        }
        return result;
    }

    public AsyncJob<Object> waitForJobDone(String job) {
        do {
            AsyncJob<Object> j = getAsyncJobClient().getAsyncJob(job);
            if (j.getStatus() != Status.IN_PROGRESS) return j;
            LOG.debug("cloudstack waiting on job "+job+": "+j);
        } while (true);
    }

    public AsyncJob<Object> waitForJob(String job) {
        AsyncJob<Object> result = waitForJobDone(job);
        if (!result.hasSucceed()) {
            LOG.warn("job {} failed: {}", job, result);
        }
        return result;
    }

    public AsyncJob<Object> waitForJobSuccess(String job) {
        AsyncJob<Object> result = waitForJobDone(job);
        if (result.hasSucceed()) {
            return result;
        } else {
            throw new IllegalStateException(String.format("job %s failed: %s", job, result));
        }
    }

    /** returns the count of matching networks which could not be deleted */
    public int deleteNetworksWhereNameMatchesRegex(String regex, boolean waitForExpunged) {
        // as needed, delete them:
        CloudstackNew40FeaturesClient client = this;
        int delCount = 0, nonDelCount = 0;
        Set<Network> nws = client.getNetworkClient().listNetworks();
        List<String> jobs = new ArrayList<String>();
        for (Network nwi: nws) {
            try {
                if (nwi.getName().matches(regex)) {
                    LOG.debug("deleting "+nwi);
                    String job = client.getNetworkClient().deleteNetwork(nwi.getId());
                    LOG.debug("deleting "+nwi+" - job "+job);
                    if (job!=null) jobs.add(job);
                    delCount++;
                } else {
                    LOG.debug("skipping deletion of (non-matched) "+nwi);
                }
            } catch (Exception e) {
                nonDelCount++;
                LOG.info("not allowed to delete "+nwi+" (may have un-expunged VM's): "+e);
            }
        }
        waitForJobs(jobs);
        if (delCount>0) LOG.info("deleted "+delCount+" networks");
        if (nonDelCount==0 && waitForExpunged) {
            int loops = 0;
            while (true) {
                boolean match = false;
                nws = client.getNetworkClient().listNetworks();
                for (Network nw: nws) {
                    match |= nw.getName().matches(regex);
                }
                if (!match) {
                    if (loops>0)
                        LOG.info("Networks now all expunged");
                    break;
                }
                if (loops==0)
                    LOG.info("waiting for networks to be expunged");
                else
                    LOG.debug("still waiting for networks to be expunged");
                loops++;
                Time.sleep(2000);
            }
        }
        return nonDelCount;
    }

    public String getNetworkOfferingWithName(String name) {
        Set<NetworkOffering> offerings = getOfferingClient().listNetworkOfferings(ListNetworkOfferingsOptions.Builder.name(name));
        // above match is _containment_ not exact, so do further filtering
        for (NetworkOffering n: offerings)
            if (name.equals(n.getName())) return n.getId();
        return null;
    }

    public boolean tryCreateNetworkAclAllEgress(String networkid, String cidrlist) {
        try {
            createNetworkAclAllEgress(networkid, cidrlist);
            return true;
        } catch (Exception e) {
            LOG.warn("Unable to create egress ACL for network "+networkid+" (may already be in place)");
            LOG.debug("Reason couldn't create egress ACL: "+e, e);
            Exceptions.propagateIfFatal(e);
            return false;
        }
    }

    public void createNetworkAclAllEgress(String networkid, String cidrlist) {
        createNetworkAclEgressTcpAndUdp(networkid, cidrlist, 1, 65535);
        // TODO ICMP
    }

    public void createNetworkAclEgressTcpAndUdp(String networkid,
            String cidrlist, Integer startport, Integer endport) {
        createVpcNetworkAcl(networkid, "TCP", cidrlist, startport, endport, null, null, "Egress");
        createVpcNetworkAcl(networkid, "UDP", cidrlist, startport, endport, null, null, "Egress");
    }

    public void createNetworkAclEdgressTcpOrUdp(String networkid, String protocol,
            String cidrlist, Integer startport, Integer endport) {
        createVpcNetworkAcl(networkid, protocol, cidrlist, startport, endport, null, null, "Egress");
    }

    public void createVpcNetworkAcl(
            String networkid, String protocol,
            String cidrlist,
            Integer startport, Integer endport,
            Integer icmpcode, String icmptype,
            String traffictype) {

        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "createNetworkACL");

        params.put("networkid", networkid);
        params.put("protocol", protocol);
        if (cidrlist!=null) params.put("cidrlist", cidrlist);
        if (startport!=null) params.put("startport", ""+startport);
        if (endport!=null) params.put("endport", ""+endport);
        if (icmpcode!=null) params.put("icmpcode", ""+icmpcode);
        if (icmptype!=null) params.put("icmptype", ""+icmptype);
        if (traffictype!=null) params.put("traffictype", traffictype);

        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        LOG.debug("createNetworkAcl GET "+params);

        HttpRequest request = HttpRequest.builder()
            .method("GET")
            .endpoint(this.endpoint)
            .addQueryParams(params)
            .addHeader("Accept", "application/json")
            .build();

        request = getQuerySigner().filter(request);

        HttpToolResponse response = HttpUtil.invoke(request);
        // TODO does non-2xx response need to be handled separately ?

//        JsonElement jr = json(response);
//        log.debug("createNetworkAcl GOT "+jr);
        try {
            waitForJobCompletion(response);
        } catch (InterruptedException e) {
            throw Exceptions.propagate(e);
        }
    }

    public PublicIPAddress createIpAddressForVpc(String vpcId) {
        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "associateIpAddress");

        params.put("vpcid", vpcId);

        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        LOG.debug("associateIpAddress GET "+params);

        HttpRequest request = HttpRequest.builder()
            .method("GET")
            .endpoint(this.endpoint)
            .addQueryParams(params)
            .addHeader("Accept", "application/json")
            .build();

        request = getQuerySigner().filter(request);

        HttpToolResponse response = HttpUtil.invoke(request);
        // TODO does non-2xx response need to be handled separately ?

        try {
            String result = waitForJobCompletion(response);
            return getCloudstackGlobalClient().getAddressApi().getPublicIPAddress(result);
        } catch (InterruptedException e) {
            throw Exceptions.propagate(e);
        }
    }

    protected JsonElement listPublicIpAddressesAtVpc(String vpcId) {
        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "listPublicIpAddresses");

        params.put("vpcid", vpcId);

        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        HttpRequest request = HttpRequest.builder()
            .method("GET")
            .endpoint(this.endpoint)
            .addQueryParams(params)
            .addHeader("Accept", "application/json")
            .build();

        request = getQuerySigner().filter(request);

        HttpToolResponse response = HttpUtil.invoke(request);
        JsonElement jr = json(response);
        LOG.debug(pretty(jr));

        return jr.getAsJsonObject().get("listpublicipaddressesresponse");
    }


    public void deleteIpsAtVpc(String vpcId) {
        JsonElement je = listPublicIpAddressesAtVpc(vpcId);
        LOG.debug(pretty(je));

        int i=0;
        for (JsonElement jei : je.getAsJsonObject().get("publicipaddress").getAsJsonArray()) {
            String id = jei.getAsJsonObject().get("id").getAsString();
            LOG.debug("deleting IP "+id);
            getCloudstackGlobalClient().getAddressApi().disassociateIPAddress(id);
            i++;
        }
        if (i>0) LOG.info("deleted "+i+" IP's at VPC "+vpcId);
    }

    /**
     * Create port-forward rule for a VPC.
     * <p>
     * Does <em>NOT</em> open any firewall.
     *
     * @return job id, like jclouds version but takes the network/tier ID.
     */
    public String createPortForwardRuleForVpc(String vpcTierId, String ipAddressId, Protocol protocol, int publicPort, String targetVmId, int privatePort) {
        // needed because jclouds doesn't support supplying tier ID (for VPC's)
        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "createPortForwardingRule");

        params.put("networkid", vpcTierId);
        params.put("ipaddressid", ipAddressId);
        params.put("protocol", protocol.toString());
        params.put("publicport", ""+publicPort);
        params.put("virtualmachineid", targetVmId);
        params.put("privateport", ""+privatePort);
        params.put("openfirewall", ""+false);

        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        LOG.debug("createPortForwardingRule GET "+params);

        HttpRequest request = HttpRequest.builder()
            .method("GET")
            .endpoint(this.endpoint)
            .addQueryParams(params)
            .addHeader("Accept", "application/json")
            .build();

        request = getQuerySigner().filter(request);

        HttpToolResponse response = HttpUtil.invoke(request);
        // TODO does non-2xx response need to be handled separately ?

        JsonElement jr = json(response);
        LOG.debug("createPortForwardingRule GOT "+jr);

        JsonObject jobfields = jr.getAsJsonObject().entrySet().iterator().next().getValue().getAsJsonObject();
        String jobId = jobfields.get("jobid").getAsString();
        return jobId;
    }

    /**
     * Create port-forward rule for a VM.
     * <p>
     * Does <em>NOT</em> open any firewall.
     */
    public String createPortForwardRuleForVm(String publicIpId, Protocol protocol, int publicPort, String targetVmId, int privatePort) {
        // needed because jclouds doesn't support CIDR
//        return cloudstackClient.getCloudstackGlobalClient().getFirewallClient().
//                createPortForwardingRuleForVirtualMachine(
//                        publicIpId, PortForwardingRule.Protocol.TCP, publicPort, targetVmId, privatePort).
//                getJobId();

        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "createPortForwardingRule");

        params.put("ipaddressid", publicIpId);
        params.put("protocol", protocol.toString());
        params.put("publicport", ""+publicPort);
        params.put("virtualmachineid", targetVmId);
        params.put("privateport", ""+privatePort);
        params.put("openfirewall", ""+false);

        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        LOG.debug("createPortForwardingRule GET "+params);

        HttpRequest request = HttpRequest.builder()
            .method("GET")
            .endpoint(this.endpoint)
            .addQueryParams(params)
            .addHeader("Accept", "application/json")
            .build();

        request = getQuerySigner().filter(request);

        request.getEndpoint().toString().replace("+",  "%2B");
        //request = request.toBuilder().endpoint(uriBuilder(request.getEndpoint()).query(decodedParams).build()).build();

        HttpToolResponse response = HttpUtil.invoke(request);
        // TODO does non-2xx response need to be handled separately ?

        JsonElement jr = json(response);
        LOG.debug("createPortForwardingRule GOT "+jr);

        JsonObject jobfields = jr.getAsJsonObject().entrySet().iterator().next().getValue().getAsJsonObject();
        String jobId = jobfields.get("jobid").getAsString();
        return jobId;
    }

    public void disableEgressFirewall(String networkId) {
        HttpToolResponse job1 = disableEgressFirewallForProtocol(networkId, "TCP");
        HttpToolResponse job2 = disableEgressFirewallForProtocol(networkId, "UDP");
        HttpToolResponse job3 = disableEgressFirewallForProtocol(networkId, "ICMP");
        try {
            waitForJobCompletion(job1);
            waitForJobCompletion(job2);
            waitForJobCompletion(job3);
        } catch (InterruptedException e) {
            throw Exceptions.propagate(e);
        }
    }

    private HttpToolResponse disableEgressFirewallForProtocol(String networkId, String protocol) {
        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "createEgressFirewallRule");

        params.put("networkid", networkId);
        params.put("protocol", protocol);
        params.put("cidrlist", "0.0.0.0/0");
        if (protocol.equals("TCP") || protocol.equals("UDP")) {
            params.put("startport", "1");
            params.put("endport", "65535");
        } else if (protocol.equals("ICMP")) {
            params.put("icmpcode", "-1");
            params.put("icmptype", "-1");
        } else {
            throw new IllegalArgumentException("Protocol " + protocol + " is not known");
        }

        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        LOG.debug("createEgressFirewallRule GET "+params);

        HttpRequest request = HttpRequest.builder()
            .method("GET")
            .endpoint(this.endpoint)
            .addQueryParams(params)
            .addHeader("Accept", "application/json")
            .build();

        request = getQuerySigner().filter(request);

        request.getEndpoint().toString().replace("+",  "%2B");
        //request = request.toBuilder().endpoint(uriBuilder(request.getEndpoint()).query(decodedParams).build()).build();

        HttpToolResponse response = HttpUtil.invoke(request);
        // TODO does non-2xx response need to be handled separately ?
        return response;
    }

    public Maybe<VirtualMachine> findVmByIp(final String ipAddress) {
        Set<VirtualMachine> vms = getVirtualMachineClient().listVirtualMachines();
        LOG.debug("VMs: ");
        return Maybe.of(Iterables.tryFind(vms, new Predicate<VirtualMachine>() {
            @Override
            public boolean apply(VirtualMachine vm) {
                //check first NIC for ip address
                return vm.getNICs().iterator().next().getIPAddress().equals(ipAddress);
            }
        }));
    }

    public String findVpcIdFromNetworkId(final String networkId) {
        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("command", "listNetworks");
        params.put("apiKey", this.apiKey);
        params.put("response", "json");

        HttpRequest request = HttpRequest.builder()
                .method("GET")
                .endpoint(this.endpoint)
                .addQueryParams(params)
                .addHeader("Accept", "application/json")
                .build();

        request = getQuerySigner().filter(request);

        HttpToolResponse response = HttpUtil.invoke(request);
        JsonElement offers = json(response);
        LOG.debug("LIST NETWORKS\n" + pretty(offers));
        //get the first network object
        Optional<JsonElement> matchingNetwork = Iterables.tryFind(offers.getAsJsonObject().get("listnetworksresponse")
                .getAsJsonObject().get("network").getAsJsonArray(), new Predicate<JsonElement>() {
            @Override
            public boolean apply(JsonElement jsonElement) {
                JsonObject matchingNetwork = jsonElement.getAsJsonObject();
                return matchingNetwork.get("id").getAsString().equals(networkId);
            }});

        if (matchingNetwork.isPresent()) {
            return matchingNetwork.get().getAsJsonObject().get("vpcid").getAsString();
        } else {
            LOG.warn("matching VPC for network with id:{} is not found", networkId);
            return null;
        }
    }
}
