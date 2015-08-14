package brooklyn.networking.vclouddirector.natmicroservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import org.apache.brooklyn.rest.apidoc.ApidocEndpoint;
import org.apache.brooklyn.rest.apidoc.ApidocRoot;

import brooklyn.networking.vclouddirector.natservice.resources.NatServiceResource;

public class ApidocResourceTest extends AbstractRestApiTest {

    private static final Logger LOG = LoggerFactory.getLogger(ApidocResourceTest.class);

    @Test
    public void testRootSerializesSensibly() throws Exception {
        String data = client().resource("/v1/apidoc/").get(String.class);
        LOG.info("apidoc gives: "+data);
        
        // make sure contains our expected resource, and that no scala gets in
        Assert.assertTrue(data.contains(NatServiceResource.class.getName()));
        Assert.assertFalse(data.contains("$"));
        Assert.assertFalse(data.contains("scala"));
    }
    
    @Test
    public void testCountRestResources() throws Exception {
        ApidocRoot response = client().resource("/v1/apidoc/").get(ApidocRoot.class);
        boolean found = false;
        for (ApidocEndpoint  api : response.getApidocApis()) {
            if ("/v1/nat".equals(api.getPath())) {
                found = true;
                break;
            }
        }
        Assert.assertTrue(found, "response="+response);
    }
}
