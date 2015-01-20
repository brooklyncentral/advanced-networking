package brooklyn.networking.vclouddirector.natmicroservice;

import static org.testng.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.util.Map;

import org.testng.annotations.Test;

import brooklyn.networking.vclouddirector.NatServiceDispatcher.TrustConfig;
import brooklyn.networking.vclouddirector.natmicroservice.PropertiesParser;

import com.google.common.collect.ImmutableMap;

public class PropertiesParserTest {

    @Test
    public void testParseEmpty() throws Exception {
        String input = "";
        Map<String, TrustConfig> result = PropertiesParser.parseProperties(new ByteArrayInputStream(input.getBytes()));
        assertEquals(result, ImmutableMap.of());
    }
    
    @Test
    public void testParseEndpoints() throws Exception {
        String input = 
                "foo.endpoint=myendpoint\n"+
                "foo.trustStore=mytruststore\n"+
                "foo.trustStorePassword=mytruststorepassword\n"+
                "bar.endpoint=myendpoint2\n"+
                "bar.trustStore=mytruststore2\n"+
                "bar.trustStorePassword=mytruststorepassword2\n";
        Map<String, TrustConfig> result = PropertiesParser.parseProperties(new ByteArrayInputStream(input.getBytes()));
        assertEquals(result, ImmutableMap.of(
                "myendpoint", new TrustConfig("mytruststore", "mytruststorepassword"),
                "myendpoint2", new TrustConfig("mytruststore2", "mytruststorepassword2")));
    }
}
