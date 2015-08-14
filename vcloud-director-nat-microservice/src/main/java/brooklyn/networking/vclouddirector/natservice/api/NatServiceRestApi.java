package brooklyn.networking.vclouddirector.natservice.api;

import org.codehaus.jackson.jaxrs.JacksonJsonProvider;

import com.google.common.collect.ImmutableList;

import org.apache.brooklyn.rest.apidoc.ApidocHelpMessageBodyWriter;

import brooklyn.networking.vclouddirector.natservice.resources.NatServiceResource;

public class NatServiceRestApi {

    public static ImmutableList<Object> getAllResources() {
        return ImmutableList.of(
                new JacksonJsonProvider(),
                new NatServiceResource(),
                new ApidocHelpMessageBodyWriter(),
                new ApidocResource());
    }
}
