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
package brooklyn.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.internal.EntityLocal;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.entity.core.Attributes;
import org.apache.brooklyn.entity.java.VanillaJavaAppImpl;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.sensor.core.DependentConfiguration;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;

import brooklyn.networking.tunnelling.SshTunnelling;

// FIXME An example entity; where to put this?
public class ExampleForwardingEntityImpl extends VanillaJavaAppImpl implements ExampleForwardingEntity {

    // TODO Remove duplication from JBossWithDbForwardImpl

    private static final Logger log = LoggerFactory.getLogger(ExampleForwardingEntityImpl.class);

    @Override
    protected void preStart() {
        super.preStart();
        Entity originEntity = getConfig(ORIGIN_ENTITY_FORWARDED);
        AttributeSensor<Integer> originPortAttribute = getConfig(ORIGIN_PORT_ATTRIBUTE_FORWARDED);

        try {
            // wait for the target DB to be available
            String url = Tasks.resolveValue(DependentConfiguration.attributeWhenReady(originEntity, Attributes.SERVICE_UP), String.class,
                    getExecutionContext());
            log.info("burst node "+this+" detected target DB available at "+url);

            Integer originPort = originEntity.getAttribute(originPortAttribute);
            SshMachineLocation fakeDb = (SshMachineLocation) getMachineOrNull();
            SshMachineLocation realDb = (SshMachineLocation) Iterables.getFirst( ((EntityLocal)originEntity).getLocations(), null );
            String originPublicKeyData = originEntity.getAttribute(PUBLIC_KEY_DATA);

            if (originPublicKeyData==null) {
                synchronized (realDb) {
                    originPublicKeyData = SshTunnelling.generateRsaKey(realDb);
                    ((EntityLocal)originEntity).setAttribute(PUBLIC_KEY_DATA, originPublicKeyData );
                }
            }

            SshTunnelling.authorizePublicKey(fakeDb, originPublicKeyData);

            SshTunnelling.openRemoteForwardingTunnel(realDb, fakeDb,
                    // on real (initiator/host) side
                    "localhost", originPort,
                    // on fake (target/new) side
                    "localhost", originPort);

        } catch (Exception e) {
            log.warn("Unable to start DB forwarding from "+originEntity+" to "+this+": "+e);
            log.debug("Unable to start DB forwarding from "+originEntity+" to "+this+": "+e, e);
            Exceptions.propagateIfFatal(e);
        }

        // after this method (preStart) it otherwise acts as normal...
    }
}
