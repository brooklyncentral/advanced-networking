package brooklyn.networking.vclouddirector.natservice.api;

import org.codehaus.jackson.jaxrs.JacksonJsonProvider;

import brooklyn.networking.vclouddirector.natservice.resources.NatServiceResource;
import brooklyn.rest.apidoc.ApidocHelpMessageBodyWriter;

import com.google.common.collect.ImmutableList;

public class NatServiceRestApi {

    public static ImmutableList<Object> getAllResources() {
        return ImmutableList.of(
                new JacksonJsonProvider(),
                new NatServiceResource(),
                new ApidocHelpMessageBodyWriter(),
                new ApidocResource());
    }
}
