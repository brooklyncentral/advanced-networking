/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.networking.vclouddirector.natmicroservice;

import java.util.logging.Level;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import brooklyn.networking.vclouddirector.NatServiceDispatcher;
import brooklyn.networking.vclouddirector.natservice.api.NatServiceRestApi;
import brooklyn.networking.vclouddirector.natservice.resources.AbstractRestResource;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.core.DefaultResourceConfig;
import com.sun.jersey.spi.inject.Errors;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.LowLevelAppDescriptor;

public abstract class AbstractRestApiTest {

    // See brooklyn.rest.testing.BrooklynRestApiTest and brooklyn.rest.testing.BrooklynRestResourceTest.
    // This is a cut-down version of those two classes.
    
    private NatServiceDispatcher dispatcher;
    protected boolean useLocalScannedCatalog = false;
    
    private JerseyTest jerseyTest;
    protected DefaultResourceConfig config = new DefaultResourceConfig();

    @BeforeClass(alwaysRun = true)
    public void setUp() throws Exception {
        dispatcher = newNatServiceDispatcher();

        config = new DefaultResourceConfig();
        
        // need this to debug jersey inject errors
        java.util.logging.Logger.getLogger(Errors.class.getName()).setLevel(Level.INFO);

        // seems we have to provide our own injector because the jersey test framework 
        // doesn't inject ServletConfig and it all blows up
        // and the servlet config provider must be an instance; addClasses doesn't work for some reason
        config.getSingletons().add(new NullServletConfigProvider());
        config.getClasses().add(NullHttpServletRequestProvider.class);
        
        for (Object r: NatServiceRestApi.getAllResources()) {
            if (r instanceof AbstractRestResource) {
                ((AbstractRestResource)r).injectNatServiceDispatcher(dispatcher);
            }
            config.getSingletons().add(r);
        }
        
        jerseyTest = new JerseyTest() {
            @Override
            protected AppDescriptor configure() {
                return new LowLevelAppDescriptor.Builder(config).build();
            }
        };
        jerseyTest.setUp();
    }

    @AfterClass(alwaysRun = false)
    public void tearDown() throws Exception {
        if (jerseyTest != null) jerseyTest.tearDown();
        config = null;
    }
    
    protected NatServiceDispatcher newNatServiceDispatcher() {
        return NatServiceDispatcher.builder().build();
    }
    
    public Client client() {
        return jerseyTest.client();
    }
}
