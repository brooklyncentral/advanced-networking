package brooklyn.networking.vclouddirector.natmicroservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

/**
 * Tests against TAI 2.0 (i.e. a vcloud-director environment kinds made available by Canopy Cloud).
 */
@Test
public class NatServiceMicroserviceTAI2LiveTest extends NatServiceMicroserviceAbstractLiveTest {

    @SuppressWarnings("unused")
    private static final Logger LOG = LoggerFactory.getLogger(NatServiceMicroserviceTAI2LiveTest.class);

    @Override
    protected String getLocationSpec() {
        return "Canopy_TAI_2_CCT001_WIN";
    }
    
    @Test(groups="Live")
    public void testOpenAndDeleteNatRule() throws Exception {
        super.testOpenAndDeleteNatRule();
    }
}
