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
package brooklyn.networking.tunnelling;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.testng.reporters.Files;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.entity.core.Entities;
import org.apache.brooklyn.entity.factory.ApplicationBuilder;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.net.Networking;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.text.Identifiers;

public class SshTunnellingIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SshTunnellingIntegrationTest.class);

    protected TestApplication app;
    protected ManagementContext managementContext;
    protected SshMachineLocation localMachine;

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        app = ApplicationBuilder.newManagedApp(TestApplication.class);
        managementContext = app.getManagementContext();

        localMachine = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", Networking.getLocalHost()));
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (managementContext != null) Entities.destroyAll(managementContext);
    }

    // TODO This code checks if ~/.ssh/id_rsa already exists, and if it does then it's a no-op.
    // But uncomfortable including this code in automated tests just in case! Hence the guards in finally block.
    @Test(groups="Integration")
    public void testGenerateRsaKeyWithDefaultFile() throws Exception {
        File privateKeyFile = new File(Os.tidyPath("~/.ssh/id_rsa"));
        File publicKeyFile = new File(Os.tidyPath("~/.ssh/id_rsa.pub"));
        String origPrivateKeyData = privateKeyFile.exists() ? Files.readFile(privateKeyFile) : null;
        String origPublicKeyData = publicKeyFile.exists() ? Files.readFile(publicKeyFile) : null;

        try {
            String publicKeyData = SshTunnelling.generateRsaKey(localMachine);
            assertEquals(publicKeyData, Files.readFile(publicKeyFile));
        } finally {
            if (origPublicKeyData != null && !origPublicKeyData.equals(Files.readFile(publicKeyFile))) {
                // Yikes! We've destroyed the developer's publicKeyFile!
                // Let's try to restore it!
                log.error("Sorry! Test overwrote "+publicKeyFile+"; attempting to restore");
                Files.writeFile(origPublicKeyData, publicKeyFile);
            }
            if (origPrivateKeyData != null && !origPrivateKeyData.equals(Files.readFile(privateKeyFile))) {
                // Yikes!
                log.error("Sorry! Test overwrote "+privateKeyFile+"; attempting to restore");
                Files.writeFile(origPrivateKeyData, privateKeyFile);
            }
        }
    }

    @Test(groups="Integration")
    public void testGenerateRsaKeyWithRandomFile() throws Exception {
        String privateKeyFile = "~/.ssh/id_rsa_"+Identifiers.makeRandomId(8);
        String publicKeyFile = privateKeyFile + ".pub";
        String publicKeyData = SshTunnelling.generateRsaKey(localMachine, privateKeyFile);
        try {
            assertEquals(publicKeyData, Files.readFile(new File(Os.tidyPath(publicKeyFile))));
        } finally {
            new File(privateKeyFile).delete();
            new File(publicKeyFile).delete();
        }
    }

    // TODO creates a random key and adds it to ~/.ssh/authorized_keys: annoying for developers!
    @Test(groups={"Integration"})
    public void testAuthorizePublicKey() throws Exception {
        String privateKeyFile = "~/.ssh/id_rsa_"+Identifiers.makeRandomId(8);
        String publicKeyFile = privateKeyFile + ".pub";
        String publicKeyData = SshTunnelling.generateRsaKey(localMachine, privateKeyFile);
        String privateKeyData = Files.readFile(new File(Os.tidyPath(privateKeyFile)));

        SshTunnelling.authorizePublicKey(localMachine, publicKeyData);

        SshMachineLocation viaForwarding = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", localMachine.getAddress())
                .configure("port", localMachine.getPort())
                .configure("user", localMachine.getUser())
                .configure("privateKeyData", privateKeyData));
        try {
            assertTrue(viaForwarding.isSshable(), "hostAndPort="+viaForwarding);
        } finally {
            viaForwarding.close();
            managementContext.getLocationManager().unmanage(viaForwarding);
            new File(privateKeyFile).delete();
            new File(publicKeyFile).delete();
        }
    }

    @Test(groups={"Integration"})
    public void testOpenRemoteForwardingTunnel() throws Exception {
        String remoteFile = "/tmp/nc.out-"+Identifiers.makeRandomId(8);
        File tempFile = File.createTempFile("test-nc", ".out");

        SshMachineLocation realDb = localMachine;
        SshMachineLocation fakeDb = localMachine;

        try {
            // Set up `nc` (and will you that to setup server-side port listener, and to connect to it)
            realDb.execScript("install-nc", ImmutableList.of(BashCommands.installPackage(ImmutableMap.of(), "netcat")));

            // Set up remote forwarding, so can connect to this port on fakeDb via its port
            String originPublicKeyData = SshTunnelling.generateRsaKey(realDb);
            SshTunnelling.authorizePublicKey(fakeDb, originPublicKeyData);
            SshTunnelling.openRemoteForwardingTunnel(realDb, fakeDb,
                    // on real (initiator/host) side
                    "localhost", 24684,
                    // on fake (target/new) side
                    "localhost", 24685);

            // Set up `nc`, so can connect
            realDb.execScript("run-nc-listener", ImmutableList.of("nohup nc -l 24684 > "+remoteFile+" &"));
            fakeDb.execScript("run-nc-connector", ImmutableList.of("echo \"myexample\" | nc localhost 24685"));

            realDb.copyFrom(remoteFile, tempFile.getAbsolutePath());
            String transferedData = Files.readFile(tempFile);

            assertEquals(transferedData, "myexample\n");
        } finally {
            tempFile.delete();
            realDb.execScript("kill-tunnel", Arrays.asList("kill `ps aux | grep ssh | grep \"localhost:24684\" | awk '{print $2}'`"));
        }
    }
}
