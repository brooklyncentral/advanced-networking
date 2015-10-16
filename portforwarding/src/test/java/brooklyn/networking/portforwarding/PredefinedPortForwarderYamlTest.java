package brooklyn.networking.portforwarding;

import static org.testng.Assert.assertEquals;

import java.io.StringReader;
import java.util.Map;

import org.apache.brooklyn.core.location.Machines;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import com.google.common.base.Joiner;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.camp.brooklyn.AbstractYamlTest;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.software.base.EmptySoftwareProcess;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.EntityTestUtils;
import org.apache.brooklyn.util.net.UserAndHostAndPort;

public class PredefinedPortForwarderYamlTest extends AbstractYamlTest {
    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(PredefinedPortForwarderYamlTest.class);

    @Test
    public void testByonPortMapping() throws Exception {
        String yaml = Joiner.on("\n").join(
                "location:",
                "  byon:",
                "    hosts:",
                "    - ssh: 10.0.0.1:22",
                "      privateAddresses: [10.0.0.1]",
                "      tcpPortMappings: {22: \"83.222.229.1:12001\", 8080: \"83.222.229.1:12002\"}",
                "      password: mypassword",
                "      user: myuser",
                "      onbox.base.dir.skipResolution: true",
                "services:",
                "- type: brooklyn.networking.subnet.SubnetTier",
                "  brooklyn.config:",
                "    subnet.portForwarder.type: brooklyn.networking.portforwarding.PredefinedPortForwarder",
                "  brooklyn.children:",
                "  - type: org.apache.brooklyn.entity.software.base.EmptySoftwareProcess",
                "    brooklyn.initializers:",
                "    - type: org.apache.brooklyn.core.sensor.StaticSensor",
                "      brooklyn.config:",
                "        name: http.port",
                "        targetType: Integer",
                "        static.value: 8080",
                "    brooklyn.config:",
                "      subnet.publiclyForwardedPorts: ",
                "      - $brooklyn:sensor(\"http.port\")");

        Entity app = createStartWaitAndLogApplication(new StringReader(yaml));
        EmptySoftwareProcess entity = (EmptySoftwareProcess) Iterables.find(Entities.descendants(app), Predicates.instanceOf(EmptySoftwareProcess.class));
        SshMachineLocation machine = Machines.findUniqueMachineLocation(entity.getLocations(), SshMachineLocation.class).get();
        
        assertMachine(machine, UserAndHostAndPort.fromParts("myuser", "83.222.229.1", 12001), ImmutableMap.of(
                SshMachineLocation.PASSWORD.getName(), "mypassword"));
        
        EntityTestUtils.assertAttributeEquals(entity, Attributes.SUBNET_ADDRESS, "10.0.0.1");
        EntityTestUtils.assertAttributeEquals(entity, Attributes.ADDRESS, "83.222.229.1");
        EntityTestUtils.assertAttributeEquals(entity, Attributes.HTTP_PORT, 8080);
        EntityTestUtils.assertAttributeEqualsEventually(entity, Sensors.newStringSensor("mapped.http.port"), "83.222.229.1:12002");
    }
    
    private void assertMachine(SshMachineLocation machine, UserAndHostAndPort conn, Map<String, ?> config) {
        assertEquals(machine.getAddress().getHostAddress(), conn.getHostAndPort().getHostText());
        assertEquals(machine.getPort(), conn.getHostAndPort().getPort());
        assertEquals(machine.getUser(), conn.getUser());
        for (Map.Entry<String, ?> entry : config.entrySet()) {
            Object actualVal = machine.getConfig(ConfigKeys.newConfigKey(Object.class, entry.getKey()));
            assertEquals(actualVal, entry.getValue());
        }
    }    
}
