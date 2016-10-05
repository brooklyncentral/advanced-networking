/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package brooklyn.networking;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.HasNetworkAddresses;
import org.apache.brooklyn.util.net.Protocol;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;

import brooklyn.networking.common.subnet.PortForwarder;
import brooklyn.networking.common.subnet.PortForwarderAsyncImpl;
import brooklyn.networking.util.TestEntity;

/**
 * Tests concurrent usage of PortForwarderAsync.
 */
public class PortForwarderAsyncTest extends BrooklynAppLiveTestSupport {

    private static final Logger LOG = LoggerFactory.getLogger(PortForwarderAsyncTest.class);
    
    private static final Duration TIMEOUT = Duration.TEN_SECONDS;
    
    private Location loc;
    private SshMachineLocation pseudoMachine;
    private TestEntity testEntity;
    
    private AtomicInteger invokeCount;
    private List<Duration> invokeTimestamps;
    private Stopwatch watch;
    private PortForwarder pf;
    private PortForwarderAsyncImpl pfa;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        loc = mgmt.getLocationRegistry().resolve("localhost");
        pseudoMachine = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("jcloudsParent", loc)
                .configure("address", "1.1.1.1")
                .configure("port", 2000)
                .configure("user", "myname"));
        
        testEntity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        
        invokeCount = new AtomicInteger(0);
        invokeTimestamps = Lists.newCopyOnWriteArrayList();
        watch = Stopwatch.createUnstarted();
        pf = mock(PortForwarder.class);
        when(pf.openPortForwarding(any(HasNetworkAddresses.class), anyInt(), any(Optional.class), any(Protocol.class), any(Cidr.class)))
                .thenAnswer(new Answer<HostAndPort>() {
                    private final AtomicInteger nextPublicPort = new AtomicInteger(11000);
                    
                    @Override
                    public HostAndPort answer(final InvocationOnMock invocationOnMock) throws Throwable {
                        @SuppressWarnings("unchecked")
                        Optional<Integer> optionalPublicPort = invocationOnMock.getArgumentAt(2, Optional.class);
                        int publicPort = optionalPublicPort.isPresent() ? optionalPublicPort.get() : nextPublicPort.getAndIncrement();
                        Duration invokeTimestamp = Duration.of(watch);
                        invokeTimestamps.add(invokeTimestamp);
                        int counter = invokeCount.incrementAndGet();
                        TimeUnit.SECONDS.sleep(1);
                        LOG.info("[openPortForwarding] -- #{} called at {}, returning after {}", new Object[] {counter, invokeTimestamp, Duration.of(watch)});
                        return HostAndPort.fromParts("1.1.1.1", publicPort);
                    }
                });
        when(pf.getPortForwardManager()).thenAnswer(Answers.RETURNS_MOCKS.get());
        
        pfa = new PortForwarderAsyncImpl((EntityLocal) testEntity, pf, null);
    }

    @Test
    public void testOpenPortForwardingWhenMachineAndPortAlreadySet() throws Exception {
        runOpenPortForwarding(true, true);
    }

    @Test
    public void testOpenPortForwardingWhenNeitherMachineNorPortAlreadySet() throws Exception {
        runOpenPortForwarding(false, false);
    }
    
    @Test
    public void testOpenPortForwardingWhenMachineNotSet() throws Exception {
        runOpenPortForwarding(false, true);
    }
    
    @Test
    public void testOpenPortForwardingWhenPortNotSet() throws Exception {
        runOpenPortForwarding(true, false);
    }

    protected void runOpenPortForwarding(boolean machineAlreadySet, boolean portAlreadySet) throws Exception {
        // If it was done sequentially, this would take 100 seconds. So we are free to use
        // large timeouts (e.g. 30 seconds) for functional tests.
        final int NUM_PORTS = 100;
        
        if (machineAlreadySet) {
            testEntity.start(ImmutableList.<Location>of(pseudoMachine));
        } else {
            testEntity.start(ImmutableList.<Location>of());
        }

        final Map<AttributeSensor<Integer>, Integer> portAttributes = Maps.newLinkedHashMap();
        for (int i = 0; i < NUM_PORTS; i++) {
            AttributeSensor<Integer> attribute = Sensors.newIntegerSensor("test.port"+i);
            int port = 1000+i;
            portAttributes.put(attribute, port);
            if (portAlreadySet) {
                testEntity.sensors().set(attribute, port);
            }
        }

        watch.start();
        for (Map.Entry<AttributeSensor<Integer>, Integer> entry : portAttributes.entrySet()) {
            EntityAndAttribute<Integer> source = new EntityAndAttribute<>(testEntity, entry.getKey());
            Optional<Integer> optionalPublicPort = Optional.of(10000+entry.getValue());
            Protocol protocol = Protocol.ALL;
            Cidr accessingCidr = Cidr.CLASS_A;
            pfa.openPortForwardingAndAdvertise(source, optionalPublicPort, protocol, accessingCidr);
        }

        // Those calls should not have blocked; so 100 calls should have been fast
        long callDuration = watch.elapsed(TimeUnit.MILLISECONDS);
        assertTrue(callDuration < 10 * 1000, "callDuration="+callDuration);

        // If the sensor wasn't set yet, then do that now
        if (!portAlreadySet) {
            for (Map.Entry<AttributeSensor<Integer>, Integer> entry : portAttributes.entrySet()) {
                testEntity.sensors().set(entry.getKey(), entry.getValue());
            }
        }

        // If the machine wasn't set yet, then do that now
        if (!machineAlreadySet) {
            testEntity.addLocation(pseudoMachine);
        }

        // The actual work to portForwarder.openPortForwarding should have all been called concurrently.
        // So timestamps + invocationCount close together. We sould also receive only the expected
        // number of calls, and no more.
        Asserts.succeedsEventually(ImmutableMap.of("timeout", TIMEOUT), new Runnable() {
            @Override public void run() {
                assertEquals(invokeCount.get(), NUM_PORTS);
            }});
        for (Duration invokeTimestamp : invokeTimestamps) {
            assertTrue(invokeTimestamp.isShorterThan(TIMEOUT), "timestamp="+invokeTimestamp);
        }
        assertEquals(invokeCount.get(), NUM_PORTS);
        
        // And expect (after the sleep) for those mapped-ports to be set as sensors
        Asserts.succeedsEventually(ImmutableMap.of("timeout", TIMEOUT), new Runnable() {
            @Override public void run() {
                Date startedAssertAt = new Date();
                List<String> nullSensors = Lists.newArrayList();
                for (AttributeSensor<Integer> sourceSensor : portAttributes.keySet()) {
                    String sensorName = "mapped.endpoint."+sourceSensor.getName();
                    AttributeSensor<String> targetSensor = Sensors.newStringSensor(sensorName);
                    if (testEntity.getAttribute(targetSensor) == null) {
                        nullSensors.add(sensorName);
                    }
                }
                if (nullSensors.size() > 0) {
                    throw new RuntimeException(+nullSensors.size()+ " null sensors (started assert at "+Time.makeDateString(startedAssertAt)+", now "+Time.makeDateString(new Date())+": "+nullSensors);
                }
            }});
    }
}
