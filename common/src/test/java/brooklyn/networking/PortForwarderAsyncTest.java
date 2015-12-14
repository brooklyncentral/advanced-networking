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

import brooklyn.networking.common.subnet.PortForwarder;
import brooklyn.networking.common.subnet.PortForwarderAsyncImpl;
import brooklyn.networking.util.TestEntity;
import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.HasNetworkAddresses;
import org.apache.brooklyn.util.net.Protocol;
import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author m4rkmckenna on 11/12/2015.
 */
public class PortForwarderAsyncTest extends BrooklynAppLiveTestSupport {

    private static final Logger LOG = LoggerFactory.getLogger(PortForwarderAsyncTest.class);
    private Location loc;
    private SshMachineLocation pseudoMachine;

    @BeforeMethod
    public void setup() throws Exception {
        super.setUp();
        loc = mgmt.getLocationRegistry().resolve("localhost");
        pseudoMachine = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("jcloudsParent", loc)
                .configure("address", "1.1.1.1")
                .configure("port", 2000)
                .configure("user", "myname"));
    }

    @Test
    public void testExpectedBehaviour() throws InterruptedException {
        final AtomicInteger invokeCount = new AtomicInteger(0);
        final TestEntity testEntity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        testEntity.start(ImmutableList.<Location>of(pseudoMachine));
        final Stopwatch watch = Stopwatch.createUnstarted();
        final PortForwarder pf = mock(PortForwarder.class);
        when(pf.openPortForwarding(any(HasNetworkAddresses.class), anyInt(), any(Optional.class), any(Protocol.class), any(Cidr.class)))
                .thenAnswer(new Answer<HostAndPort>() {
                    @Override
                    public HostAndPort answer(final InvocationOnMock invocationOnMock) throws Throwable {
                        TimeUnit.SECONDS.sleep(10);
                        LOG.info("[openPortForwarding] -- #{} returning after [{}]us", invokeCount.incrementAndGet(), watch.elapsed(TimeUnit.MICROSECONDS));
                        return HostAndPort.fromParts("1.1.1.1", 2000);
                    }
                });
        when(pf.getPortForwardManager()).thenAnswer(Answers.RETURNS_MOCKS.get());
        final PortForwarderAsyncImpl pfa = new PortForwarderAsyncImpl((EntityLocal) testEntity, pf, null);

        EntityAndAttribute<Integer> source = new EntityAndAttribute<>(testEntity, TestEntity.PORT);
        Optional<Integer> optionalPublicPort = Optional.of(9999);
        Protocol protocol = Protocol.ALL;
        Cidr accessingCidr = Cidr.CLASS_A;

        watch.start();
        pfa.openPortForwardingAndAdvertise(source, optionalPublicPort, protocol, accessingCidr);
        for (int i = 0; i < 100; i++) {
            testEntity.populatePort(i + 1000);
            testEntity.addLocation(loc);
        }
        assertThat(invokeCount.get()).isEqualTo(0); // invoke count should be 0
        TimeUnit.MILLISECONDS.sleep(10500);// wait 10.5 seconds for all calls to return
        assertThat(invokeCount.get()).isEqualTo(200); // 200 as 2 events are triggered
    }


}
