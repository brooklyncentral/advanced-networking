package brooklyn.networking.vclouddirector.natmicroservice;

import static org.testng.Assert.assertTrue;
import io.airlift.command.ParseOptionMissingException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
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
        PrintStream outOrig = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream outCustom = new PrintStream(baos, true);
        System.setOut(outCustom);
        try {
            Callable<?> command = new NatMicroServiceMain().cliBuilder().build().parse("help");
            command.call();
            
            String out = new String(baos.toByteArray());
            assertTrue(out.contains("usage: nat-microservice"), "out="+out);
        } finally {
            System.setOut(outOrig);
        }
    }
    
    @Test
    public void testInfo() throws Exception {
        PrintStream outOrig = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream outCustom = new PrintStream(baos, true);
        System.setOut(outCustom);
        try {
            Callable<?> command = new NatMicroServiceMain().cliBuilder().build().parse("info");
            command.call();
            
            String out = new String(baos.toByteArray());
            assertTrue(out.contains("Version:"), "out="+out);
            assertTrue(out.contains("Website:"), "out="+out);
            assertTrue(out.contains("Source:"), "out="+out);
        } finally {
            System.setOut(outOrig);
        }
    }
    
    @Test
    public void testLaunchFailsIfNoEndpointsFile() throws Exception {
        try {
            Callable<?> command = new NatMicroServiceMain().cliBuilder().build().parse("launch");
            command.call();
        } catch (ParseOptionMissingException e) {
            if (!e.toString().contains("Required option '--endpointsProperties' is missing")) throw e;
        }
    }
    
    @Test
    public void testLaunchFailsIfInvalidPortRange() throws Exception {
        File propertiesFile = File.createTempFile("endpoints-testLaunchFailsIfInvalidPortRange", "properties");
        try {
            // range is too big; expect failure
            Callable<?> command = new NatMicroServiceMain().cliBuilder().build().parse("launch", 
                    "--endpointsProperties", propertiesFile.getAbsolutePath(), 
                    "--publicPortRange", "1024-100000");
            command.call();
            Assert.fail();
        } catch (IllegalArgumentException e) {
            if (!e.toString().contains("out of range")) throw e;
        } finally {
            propertiesFile.delete();
        }
    }
}
