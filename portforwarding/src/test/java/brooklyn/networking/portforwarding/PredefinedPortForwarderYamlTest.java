package brooklyn.networking.portforwarding;

import static org.testng.Assert.assertEquals;

import java.io.StringReader;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import com.google.common.base.Joiner;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.EmptySoftwareProcess;
import brooklyn.entity.basic.Entities;
import brooklyn.event.basic.Sensors;
import brooklyn.location.basic.Machines;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.test.EntityTestUtils;
import brooklyn.util.net.UserAndHostAndPort;
import io.brooklyn.camp.brooklyn.AbstractYamlTest;

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
                "  - type: brooklyn.entity.basic.EmptySoftwareProcess",
                "    brooklyn.initializers:",
                "    - type: brooklyn.entity.software.StaticSensor",
                "      brooklyn.config:",
                "        name: http.port",
                "        targetType: Integer",
                "        static.value: 8080",
                "    brooklyn.config:",
                "      subnet.publiclyForwardedPorts: ",
                "      - $brooklyn:sensor(\"http.port\")");

        Entity app = createStartWaitAndLogApplication(new StringReader(yaml));
        EmptySoftwareProcess entity = (EmptySoftwareProcess) Iterables.find(Entities.descendants(app), Predicates.instanceOf(EmptySoftwareProcess.class));
        SshMachineLocation machine = Machines.findUniqueSshMachineLocation(entity.getLocations()).get();
        
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
