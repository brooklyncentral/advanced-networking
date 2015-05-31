/*
 * Copyright 2013-2014 by Cloudsoft Corporation Limited
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
package brooklyn.networking.portforwarding;

import static org.testng.Assert.assertTrue;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Entities;
import brooklyn.location.LocationSpec;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.JcloudsSshMachineLocation;
import brooklyn.management.ManagementContext;
import brooklyn.test.entity.TestApplication;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.net.Networking;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

// TODO Tests currently written to be fast: they assume existing VMs are configured.
// Could re-write to create the VMs (and terminate them obviously) for each run.
public abstract class AbstractDockerPortForwarderTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractDockerPortForwarderTest.class);

    // FIXME These need to be changed for each user
    protected static final String DOCKER_HOST_IP = "127.0.0.1";
    protected static final String LOC_IMAGE_ID = "344bced75bd4206ef5a70fd7788894a92a7a5a2b3b2b2cae863b2e40d11e5c99";
    protected static final int DOCKER_HOST_PORT = 4243;

    protected static final String USER = "root";
    protected static final String PASSWORD = "password";

    protected static final String LOC_SPEC = "docker:http://"+DOCKER_HOST_IP+":"+DOCKER_HOST_PORT;
    protected static final String LOC_HARDWARE_ID = "small";

    protected ManagementContext managementContext;
    protected PortForwardManager portForwardManager;
    protected TestApplication app;
    protected DockerPortForwarder portForwarder;
    protected ExecutorService executor;

    protected JcloudsLocation loc;
    protected SshMachineLocation privateMachine;
    protected String publicIp;
    protected String privateIp;

    @BeforeClass(alwaysRun = true)
    public void setUpClass() throws Exception {
        managementContext = Entities.newManagementContext();
        portForwardManager = (PortForwardManager) managementContext.getLocationRegistry().resolve("portForwardManager(scope=global)");

        // Note: using different username on each to ensure no mix up there!
        loc = (JcloudsLocation) managementContext.getLocationRegistry().resolve(LOC_SPEC, MutableMap.<String, Object>builder()
                .put("identity", "notused")
                .put("credential", "notused")
                .build());

        // Start the VMs in parallel
        executor = Executors.newCachedThreadPool();

        Future<SshMachineLocation> privateFuture = executor.submit(new Callable<SshMachineLocation>() {
            public SshMachineLocation call() throws Exception {
                return (SshMachineLocation) loc.obtain(MutableMap.<String, Object>builder()
                        .put("imageId", LOC_IMAGE_ID)
                        .put("hardwareId", LOC_HARDWARE_ID)
                        .put("inboundPorts", ImmutableList.of(22, 8080))
                        .put("tags", ImmutableList.of(getClass().getName() + "-privateTarget"))
                        .put("user", USER)
                        .put("password", PASSWORD)
                        .build());
            }
        });

        privateMachine = privateFuture.get();
        privateIp = Iterables.get(((JcloudsSshMachineLocation) privateMachine).getNode().getPrivateAddresses(), 0);
        publicIp = privateMachine.getAddress().getHostAddress();
    }

    @AfterClass(alwaysRun=true)
    public void tearDownClass() throws Exception {
        if (managementContext != null) Entities.destroyAll(managementContext);
        if (executor != null) executor.shutdownNow();
    }

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        app = ApplicationBuilder.newManagedApp(TestApplication.class, managementContext);
        portForwarder = new DockerPortForwarder(portForwardManager);
        portForwarder.init(DOCKER_HOST_IP, DOCKER_HOST_PORT);
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (app != null) Entities.destroy(app);
        app = null;
        portForwarder = null;
    }

    protected void assertTargetSshableVia(HostAndPort hostAndPort) throws Exception {
        log.info("Confirming ssh-reachable: "+privateMachine.getUser()+"@"+hostAndPort.getHostText()+":"+hostAndPort
                .getPort());

        SshMachineLocation viaForwarding = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", Networking.getInetAddressWithFixedName(hostAndPort.getHostText()))
                .configure("port", hostAndPort.getPort())
                .configure("user", privateMachine.getUser())
                .configure("password", PASSWORD));
        try {
            assertTrue(viaForwarding.isSshable(), "hostAndPort="+hostAndPort);

            // FIXME Does this `nc` test actually work? Needs further investigation, and assumptions documented.
            viaForwarding.execCommands("run nc on port 8080", ImmutableList.of("nc -l 8080"));
        } finally {
            viaForwarding.close();
            managementContext.getLocationManager().unmanage(viaForwarding);
        }
    }

}
