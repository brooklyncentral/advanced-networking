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

import static org.apache.brooklyn.util.ssh.BashCommands.sudo;
import static org.testng.Assert.assertTrue;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.location.access.PortForwardManager;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsSshMachineLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.net.Networking;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;

// TODO Tests currently written to be fast: they assume existing VMs are configured.
// Could re-write to create the VMs (and terminate them obviously) for each run.
public abstract class AbstractPortForwarderIptablesTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractPortForwarderIptablesTest.class);

    protected static final boolean USE_EXISTING_MACHINES = false;
    private final String existingForwarderUser = "brooklynforwarder";
    //private final String existingForwarderPublicIp = "23.20.176.93";
    private final String existingForwarderHostname = "ec2-23-20-176-93.compute-1.amazonaws.com";
    private final String existingForwarderPrivateKeyFile = Os.tidyPath("~/.ssh/id_rsa");
    private final String existingTargetUser = "brooklyntarget";
    //private final String existingTargetPublicIp = "107.20.101.61";
    private final String existingTargetPrivateIp = "10.45.152.220";
    private final String existingTargetPrivateKeyFile = existingForwarderPrivateKeyFile;

    protected final String locSpec = "jclouds:aws-ec2:us-east-1";
    protected final String locImageId = "us-east-1/ami-7d7bfc14";
    protected final String locHardwareId = "m1.small";

    protected ManagementContext managementContext;
    protected PortForwardManager portForwardManager;
    protected PortForwarderIptables portForwarder;
    protected TestApplication app;
    protected ExecutorService executor;

    protected JcloudsLocation loc; // will be null if USE_EXISTING_MACHINES
    protected SshMachineLocation forwarderMachine;
    private SshMachineLocation targetPublicMachine;
    protected SshMachineLocation targetPrivateMachine;
    protected String forwarderPublicIp;
    protected String targetPrivateIp;
    protected String portForwarderType = PortForwarderIptables.class.getName();
    protected HostAndPort frontEndHostAndPort;

    /** set true for live tests, false for unit tests */
    protected abstract boolean requiresMachines();

    protected ManagementContext newManagementContext() {
        return Entities.newManagementContext();
    }

    @BeforeClass(alwaysRun=true)
    public void setUpClass() throws Exception {
        managementContext = newManagementContext();
        portForwardManager = (PortForwardManager) managementContext.getLocationRegistry().resolve("portForwardManager(scope=global)");
        portForwarder = new PortForwarderIptables(portForwardManager, forwarderPublicIp, forwarderMachine);
        
        if (!requiresMachines()) {
            // no op; many fields will be null

        } else {
            if (USE_EXISTING_MACHINES) {
                forwarderMachine = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                    .configure("address", Networking.getInetAddressWithFixedName(existingForwarderHostname))
                    .configure("user", existingForwarderUser)
                    .configure("privateKeyFile", existingForwarderPrivateKeyFile));

                targetPrivateMachine = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                    .configure("address", Networking.getInetAddressWithFixedName(existingTargetPrivateIp))
                    .configure("user", existingTargetUser)
                    .configure("privateKeyFile", existingTargetPrivateKeyFile));

                targetPrivateIp = existingTargetPrivateIp;

            } else {
                // Note: using different username on each to ensure no mix up there!
                loc = (JcloudsLocation) managementContext.getLocationRegistry().resolve(locSpec);

                // Start the VMs in parallel
                executor = Executors.newCachedThreadPool();
                Future<SshMachineLocation> forwarderFuture = executor.submit(new Callable<SshMachineLocation>() {
                    public SshMachineLocation call() throws Exception {
                        return (SshMachineLocation) loc.obtain(MutableMap.<String,Object>builder()
                            .put("imageId", locImageId)
                            .put("hardwareId", locHardwareId)
                            .put("inboundPorts", ImmutableList.of(22, 11001, 11002, 11003, 11004, 11005, 11006, 11007, 11008, 11009, 11010))
                            .put("tags", ImmutableList.of(getClass().getName()+"-forwarder"))
                            .put("user", "brooklynforwarder")
                            .build());
                    }
                });
                Future<SshMachineLocation> targetFuture = executor.submit(new Callable<SshMachineLocation>() {
                    public SshMachineLocation call() throws Exception {
                        return (SshMachineLocation) loc.obtain(MutableMap.<String,Object>builder()
                            .put("imageId", locImageId)
                            .put("hardwareId", locHardwareId)
                            .put("inboundPorts", ImmutableList.of(22))
                            .put("tags", ImmutableList.of(getClass().getName()+"-privateTarget"))
                            .put("user", "brooklyntarget")
                            .build());
                    }
                });

                forwarderMachine = forwarderFuture.get();
                targetPublicMachine = targetFuture.get();

                // See http://www.centosblog.com/iptables-restorecon-command-not-found/
                //  * sysctl -w net.ipv4.ip_forward=1
                forwarderMachine.execScript("install-policycoreutils", ImmutableList.of(BashCommands.installPackage(ImmutableMap.of("yum", "policycoreutils"), null)));
                forwarderMachine.execScript("enable-ip-forward", ImmutableList.of(sudo("sysctl -w net.ipv4.ip_forward=1")));

                targetPrivateIp = Iterables.get(((JcloudsSshMachineLocation)targetPublicMachine).getNode().getPrivateAddresses(), 0);

                targetPrivateMachine = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                    .configure("address", Networking.getInetAddressWithFixedName(targetPrivateIp))
                    .configure("user", targetPublicMachine.getUser())
                    .configure("privateKeyData", targetPublicMachine.getConfig(SshMachineLocation.PRIVATE_KEY_DATA))
                    .configure("privateKeyFile", targetPublicMachine.getConfig(SshMachineLocation.PRIVATE_KEY_FILE)));
            }

            forwarderPublicIp = forwarderMachine.getAddress().getHostAddress();
        }
    }

    @AfterClass(alwaysRun=true)
    public void tearDownClass() throws Exception {
        if (USE_EXISTING_MACHINES) {
            // FIXME Tear down frontEndHostAndPort from forwarderMachine
        } else {
            // FIXME Enable when not debugging!
//            if (loc != null && forwarderMachine != null) loc.release(forwarderMachine);
//            if (loc != null && targetPublicMachine != null) loc.release(targetPublicMachine);
        }
        if (managementContext != null) Entities.destroyAll(managementContext);
        if (executor != null) executor.shutdownNow();
    }

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        app = managementContext.getEntityManager().createEntity(EntitySpec.create(TestApplication.class));
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (app != null) Entities.destroy(app);
        app = null;
        portForwarder = null;
    }

    protected void assertTargetReachableVia(HostAndPort hostAndPort) throws Exception {
        log.info("Confirming ssh-reachable: "+targetPrivateMachine.getUser()+"@"+hostAndPort.getHost()+":"+hostAndPort.getPort());

        SshMachineLocation viaForwarding = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", Networking.getInetAddressWithFixedName(hostAndPort.getHost()))
                .configure("port", hostAndPort.getPort())
                .configure("user", targetPrivateMachine.getUser())
                .configure("privateKeyData", targetPrivateMachine.getConfig(SshMachineLocation.PRIVATE_KEY_DATA))
                .configure("privateKeyFile", targetPrivateMachine.getConfig(SshMachineLocation.PRIVATE_KEY_FILE)));
        try {
            assertTrue(viaForwarding.isSshable(), "hostAndPort="+hostAndPort);
        } finally {
            viaForwarding.close();
            managementContext.getLocationManager().unmanage(viaForwarding);
        }
    }
}
