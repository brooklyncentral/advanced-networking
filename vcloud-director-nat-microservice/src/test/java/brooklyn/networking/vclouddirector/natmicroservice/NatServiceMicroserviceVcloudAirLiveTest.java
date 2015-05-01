package brooklyn.networking.vclouddirector.natmicroservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

/**
 * Tests against vCloud Air (previously known as vCHS).
 */
@Test
public class NatServiceMicroserviceVcloudAirLiveTest extends NatServiceMicroserviceAbstractLiveTest {

    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory.getLogger(NatServiceMicroserviceVcloudAirLiveTest.class);

    @Override
    protected String getLocationSpec() {
        return "canopy-vCHS";
    }
}
