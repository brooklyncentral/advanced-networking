/*
 * Copyright 2013-2015 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.networking.util;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.apache.brooklyn.util.time.Duration;

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
