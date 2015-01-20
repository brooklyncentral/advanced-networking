package brooklyn.networking.vclouddirector.natmicroservice;

import io.airlift.command.ParseOptionMissingException;

import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class NatMicroServiceMainTest {

    private static final Logger LOG = LoggerFactory.getLogger(NatMicroServiceMainTest.class);

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
    }
    
    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
    }
    
    @Test
    public void testHelp() throws Exception {
        // Visual inspection test, that displayed correctly (without throwing exceptions)
        Callable<?> command = new NatMicroServiceMain().cliBuilder().build().parse("help");
        command.call();
    }
    
    @Test
    public void testInfo() throws Exception {
        // Visual inspection test, that displayed correctly (without throwing exceptions)
        Callable<?> command = new NatMicroServiceMain().cliBuilder().build().parse("info");
        command.call();
    }
    
    @Test
    public void testLaunchFailsIfNoEndpointsFile() throws Exception {
        // Visual inspection test, that help was displayed
        try {
            Callable<?> command = new NatMicroServiceMain().cliBuilder().build().parse("launch");
            command.call();
        } catch (ParseOptionMissingException e) {
            // success
        }
    }
}
