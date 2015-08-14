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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Atomics.newReference;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.net.Networking;
import brooklyn.util.repeat.Repeater;
import brooklyn.util.time.Duration;

@Beta
public class ConcurrentReachableAddressFinder {

    private static final Logger LOG = LoggerFactory.getLogger(ConcurrentReachableAddressFinder.class);

    public static class Reachable {
        public boolean isReachable(String addr, Duration timeout) {
            try {
                return Networking.getInetAddressWithFixedName(addr).isReachable((int) timeout.toMilliseconds());
            } catch (IOException e) {
                if (LOG.isTraceEnabled())
                    LOG.trace("Address " + addr + " not reachable", e);
                return false;
            }
        }
    }

    private final Reachable pingTester;
    private final ListeningExecutorService userExecutor;

    public ConcurrentReachableAddressFinder(ListeningExecutorService userExecutor) {
        this(new Reachable(), userExecutor);
    }

    public ConcurrentReachableAddressFinder(Reachable pingTester, ListeningExecutorService userExecutor) {
        this.pingTester = checkNotNull(pingTester, "tester");
        this.userExecutor = listeningDecorator(checkNotNull(userExecutor, "userExecutor"));
    }

    public String findReachable(Iterable<String> addresses, Duration timeout) {
        AtomicReference<String> result = newReference(); // For retrieving the reachable address (if any)
        Duration timeoutPerAttempt = Duration.min(Duration.of(2, TimeUnit.SECONDS), timeout);

        // TODO Could exit early on other conditions (e.g. node no longer running), as jclouds does
        Predicate<Iterable<String>> findOrBreak = updateRefOnAddressReachable(result, timeoutPerAttempt);

        LOG.debug("blocking on reachability of addresses {} for max {}", addresses, timeout);
        boolean passed = Repeater
                .create()
                .until(addresses, findOrBreak)
                .backoffTo(Duration.ONE_SECOND)
                .limitTimeTo(timeout)
                .run();

        if (passed) {
            LOG.debug("address {} reachable", result);
            assert result.get() != null;
            return result.get();
        } else {
            throw new NoSuchElementException("Could not connect to any address in "+addresses+" within "+timeout);
        }
    }

    /**
     * Checks if any any of the given addresses are reachable. It checks them all concurrently, and
     * sets reference if found, or returns false.
     */
    private Predicate<Iterable<String>> updateRefOnAddressReachable(final AtomicReference<String> reachableAddress, final Duration timeout) {
        return new Predicate<Iterable<String>>() {
            @Override public boolean apply(Iterable<String> input) {
                List<ListenableFuture<?>> futures = Lists.newArrayList();
                for (final String addr : input) {
                    futures.add(userExecutor.submit(new Runnable() {
                        @Override public void run() {
                            try {
                                if (pingTester.isReachable(addr, timeout)) {
                                    // only set if this was found first
                                    reachableAddress.compareAndSet(null, addr);
                                }
                            } catch (RuntimeException e) {
                                LOG.warn("Error checking reachability of ip " + addr, e);
                            }
                        }}));
                }
                try {
                    // TODO Could return faster; don't wait for all futures, just the first that succeeds
                    Futures.allAsList(futures).get();
                } catch (Exception e) {
                    throw Exceptions.propagate(e);
                }
                return reachableAddress.get() != null;
            }

            @Override
            public String toString() {
                return "setAndReturnTrueIfReachableAddressFound()";
            }
        };
    }
}
