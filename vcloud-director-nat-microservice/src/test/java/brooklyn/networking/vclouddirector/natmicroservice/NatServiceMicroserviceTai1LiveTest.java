package brooklyn.networking.vclouddirector.natmicroservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

/**
 * Tests against TAI 1.2 (i.e. a vcloud-director environment kinds made available by Canopy Cloud).
 */
@Test
public class NatServiceMicroserviceTai1LiveTest extends NatServiceMicroserviceAbstractLiveTest {

    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory.getLogger(NatServiceMicroserviceTai1LiveTest.class);

    @Override
    protected String getLocationSpec() {
        return "Canopy_TAI_TEST";
    }
}
