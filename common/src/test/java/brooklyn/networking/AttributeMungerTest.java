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
package brooklyn.networking;

import static org.testng.Assert.assertEquals;

import java.util.List;

import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.core.entity.EntityAndAttribute;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.location.SimulatedLocation;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.test.Asserts;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class AttributeMungerTest extends BrooklynAppUnitTestSupport {

    private List<Object> events;
    private Location loc;

    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        events = Lists.newCopyOnWriteArrayList();
        loc = mgmt.getLocationManager().createLocation(LocationSpec.create(SimulatedLocation.class));
        app.start(ImmutableList.of(loc));

        app.subscriptions().subscribe(app, TestApplication.MY_ATTRIBUTE, new SensorEventListener<String>() {
                @Override public void onEvent(SensorEvent<String> event) {
                    events.add(event.getValue());
                }});
    }

    @Test(groups="Integration") // has a sleep for equalsContinually
    public void testSetAttributeIfChanged() throws Exception {
        AttributeMunger.setAttributeIfChanged(new EntityAndAttribute<String>(app, TestApplication.MY_ATTRIBUTE), "val1");
        assertEquals(app.getAttribute(TestApplication.MY_ATTRIBUTE), "val1");
        assertEquals(events, ImmutableList.of("val1"), "actual="+events);

        AttributeMunger.setAttributeIfChanged(new EntityAndAttribute<String>(app, TestApplication.MY_ATTRIBUTE), "val1");
        assertEqualsContinually(events, ImmutableList.of("val1"));
        assertEquals(app.getAttribute(TestApplication.MY_ATTRIBUTE), "val1");

        AttributeMunger.setAttributeIfChanged(new EntityAndAttribute<String>(app, TestApplication.MY_ATTRIBUTE), "val2");
        assertEquals(app.getAttribute(TestApplication.MY_ATTRIBUTE), "val2");
        assertEqualsEventually(events, ImmutableList.of("val1", "val2"));
    }

    @Test
    public void testTransformSensorStringReplacingWithPublicAddressAndPort() throws Exception {
        final AttributeSensor<String> TARGET_HOSTNAME = new BasicAttributeSensor<String>(String.class, "hostname");
        final AttributeSensor<String> OTHER_HOSTNAME = new BasicAttributeSensor<String>(String.class, "otherhostname");
        final AttributeSensor<Integer> TARGET_PORT = new BasicAttributeSensor<Integer>(Integer.class, "target.port");
        final AttributeSensor<String> ENDPOINT = new BasicAttributeSensor<String>(String.class, "endpoint");
        final AttributeSensor<String> PUBLIC_ENDPOINT = new BasicAttributeSensor<String>(String.class, "publicEndpoint");

        AttributeMunger munger = new AttributeMunger(app);

        munger.transformSensorStringReplacingWithPublicAddressAndPort(
                new EntityAndAttribute<String>(app, ENDPOINT),
                Optional.of(new EntityAndAttribute<Integer>(app, TARGET_PORT)),
                ImmutableList.of(TARGET_HOSTNAME, OTHER_HOSTNAME),
                new EntityAndAttribute<String>(app, PUBLIC_ENDPOINT));

        app.sensors().set(TARGET_HOSTNAME, "myprivatehostname");
        app.sensors().set(TARGET_PORT, 1234);
        app.sensors().set(ENDPOINT, "PREFIX://myprivatehostname:1234/POSTFIX");
        app.sensors().set(PUBLIC_ENDPOINT, "mypublichostname:5678");

        EntityAsserts.assertAttributeEqualsEventually(app, ENDPOINT, "PREFIX://mypublichostname:5678/POSTFIX");
    }

    private <T> void assertEqualsEventually(T actual, T expected) {
        Asserts.eventually(Suppliers.ofInstance(actual), Predicates.equalTo(expected));
    }

    private <T> void assertEqualsContinually(T actual, T expected) {
        Asserts.continually(Suppliers.ofInstance(actual), Predicates.equalTo(expected));
    }
}
