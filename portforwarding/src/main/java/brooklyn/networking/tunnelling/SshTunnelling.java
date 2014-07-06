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
package brooklyn.networking.tunnelling;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.text.Strings;

import com.google.common.io.Files;

public class SshTunnelling {

    private static final Logger LOG = LoggerFactory.getLogger(SshTunnelling.class);

    public static String generateRsaKey(SshMachineLocation machine) {
        return generateRsaKey(machine, "~/.ssh/id_rsa");
    }

    public static String generateRsaKey(SshMachineLocation machine, String privateKeyFile) {
        String publicKeyFile = (privateKeyFile.startsWith("~/") ? privateKeyFile.substring(2) : privateKeyFile) + ".pub";
        try {
            machine.execCommands("ensuring has ssh public key",
                    Arrays.asList("if [ ! -f "+privateKeyFile+" ] ; then ssh-keygen -t rsa -N \"\" -f "+privateKeyFile+" ; fi"));
            File kf = File.createTempFile("brooklyn", "machine.id_rsa.pub");
            machine.copyFrom(publicKeyFile, kf.getAbsolutePath());
            return Files.toString(kf, Charset.defaultCharset());
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    public static void openRemoteForwardingTunnel(SshMachineLocation initiator, SshMachineLocation otherEnd,
            String initiatorNicNameToExpose, int initiatorPortToExpose,
            String optionalOtherEndNicName, int otherEndPortToEnable) {
        // create the ssh forwarder
        initiator.execScript("starting forwarding tunnel", Arrays.asList(
                "nohup bash -c 'ssh -N -o StrictHostKeyChecking=no -R " +
                        (Strings.isBlank(optionalOtherEndNicName) ? "" : optionalOtherEndNicName+":") +
                        otherEndPortToEnable + ":" +
                        initiatorNicNameToExpose + ":" +
                        initiatorPortToExpose + " " +
                        otherEnd.getUser() + "@" + otherEnd.getAddress().getHostAddress() + " " +
                                "1> /dev/null 2> /dev/null < /dev/null &'" // redirects needed to ensure termination
            ));
    }

    public static void openLocalForwardingTunnel(SshMachineLocation initiator, SshMachineLocation otherEnd,
            String optionalInitiatorNicNameToEnable, int initiatorPortToEnable,
            String otherEndNicName, int otherEndPortToExpose) {
        // create the ssh forwarder
        initiator.execScript("starting forwarding tunnel", Arrays.asList(
                "nohup bash -c 'ssh -N -o StrictHostKeyChecking=no -L " +
                        (Strings.isBlank(optionalInitiatorNicNameToEnable) ? "" : optionalInitiatorNicNameToEnable + ":") +
                        initiatorPortToEnable + ":" +
                        otherEndNicName + ":" +
                        otherEndPortToExpose + " " +
                        otherEnd.getUser() + "@" + otherEnd.getAddress().getHostAddress() + " " +
                                "1> /dev/null 2> /dev/null < /dev/null &'" // redirects needed to ensure termination
            ));
    }

    public static void authorizePublicKey(SshMachineLocation target, String originPublicKeyData) {
        target.execScript("installing public key", Arrays.asList(
                "cp ~/.ssh/authorized_keys x",
                "cat >> x << __END_OF_AUTH_KEY_DATA__\n" + originPublicKeyData+"\n" + "__END_OF_AUTH_KEY_DATA__",
                "mv x ~/.ssh/authorized_keys",
                "chmod 600 ~/.ssh/authorized_keys"
                ));
    }

}
