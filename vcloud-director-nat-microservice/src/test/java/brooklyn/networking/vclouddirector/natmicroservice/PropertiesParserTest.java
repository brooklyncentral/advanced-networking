package brooklyn.networking.vclouddirector.natmicroservice;

import static org.testng.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.util.Map;

import org.testng.annotations.Test;

import com.google.common.collect.ImmutableMap;

import org.apache.brooklyn.core.location.PortRanges;

import brooklyn.networking.vclouddirector.NatServiceDispatcher.EndpointConfig;

public class PropertiesParserTest {

    @Test
    public void testParseEmpty() throws Exception {
        String input = "";
        Map<String, EndpointConfig> result = PropertiesParser.parseProperties(new ByteArrayInputStream(input.getBytes()));
        assertEquals(result, ImmutableMap.of());
    }
    
    @Test
    public void testParseEndpoints() throws Exception {
        String input = 
                "foo.endpoint=myendpoint\n"+
                "foo.portRange=1234-5678\n"+
                "foo.trustStore=mytruststore\n"+
                "foo.trustStorePassword=mytruststorepassword\n"+
                "bar.endpoint=myendpoint2\n"+
                "bar.trustStore=mytruststore2\n"+
                "bar.trustStorePassword=mytruststorepassword2\n";
        Map<String, EndpointConfig> result = PropertiesParser.parseProperties(new ByteArrayInputStream(input.getBytes()));
        assertEquals(result, ImmutableMap.of(
                "myendpoint", new EndpointConfig(PortRanges.fromString("1234-5678"), "mytruststore", "mytruststorepassword"),
                "myendpoint2", new EndpointConfig(null, "mytruststore2", "mytruststorepassword2")));
    }
}
