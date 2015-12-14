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

package brooklyn.networking.util;

import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.entity.AbstractApplication;
import org.apache.brooklyn.core.entity.AbstractEntity;

/**
 * @author m4rkmckenna on 11/12/2015.
 */
public class TestEntityImpl extends AbstractApplication implements TestEntity {
    @Override
    public void populatePort(final Integer port) {
        sensors().set(PORT, port);
    }

    @Override
    public void addLocation(final Location location) {
        sensors().emit(AbstractEntity.LOCATION_ADDED, location);
    }
}
