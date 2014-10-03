package brooklyn.networking.util;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.util.time.Duration;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

public class ConcurrentReachableAddressFinderTest {

    private ListeningExecutorService executor;
    private ConcurrentReachableAddressFinder reachableAddressFinder;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        executor = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
        reachableAddressFinder = new ConcurrentReachableAddressFinder(executor);
    }
    
    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (executor != null) executor.shutdownNow();
    }
    
    @Test
    public void testFindsReachable() throws Exception {
        String result = reachableAddressFinder.findReachable(ImmutableList.of("127.0.0.1"), Duration.TEN_SECONDS);
        assertEquals(result, "127.0.0.1");
    }
    
    @Test
    public void testFindsReachableKeepsAddressInOriginalFormat() throws Exception {
        String result = reachableAddressFinder.findReachable(ImmutableList.of("localhost"), Duration.TEN_SECONDS);
        assertEquals(result, "localhost");
    }
    
    // TODO Integration because takes two seconds for timeout of 1.2.3.4; could make it exit immediately as soon as one connection is found.
    @Test(groups="Integration")
    public void testFindsReachableWhenMultipleAddresses() throws Exception {
        String result = reachableAddressFinder.findReachable(ImmutableList.of("1.2.3.4", "127.0.0.1"), Duration.TEN_SECONDS);
        assertEquals(result, "127.0.0.1");
    }
    
    @Test(groups="Integration")
    public void testFindsReachableWhenNoneReachable() throws Exception {
        try {
            String result = reachableAddressFinder.findReachable(ImmutableList.of("1.2.3.4", "1.2.3.5"), Duration.of(5, TimeUnit.SECONDS));
            fail("Expected NoSuchElementException; got "+result);
        } catch (NoSuchElementException e) {
            // success; check it has a nice message
            if (!e.toString().contains("Could not connect to any address")) throw e;
        }
    }
}
