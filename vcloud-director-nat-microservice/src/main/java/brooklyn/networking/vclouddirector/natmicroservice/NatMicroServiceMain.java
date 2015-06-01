
package brooklyn.networking.vclouddirector.natmicroservice;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import brooklyn.BrooklynVersion;
import brooklyn.location.basic.PortRanges;
import brooklyn.networking.vclouddirector.NatServiceDispatcher;
import brooklyn.networking.vclouddirector.NatServiceDispatcher.EndpointConfig;
import brooklyn.util.ResourceUtils;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.exceptions.FatalConfigurationRuntimeException;
import brooklyn.util.exceptions.FatalRuntimeException;
import brooklyn.util.exceptions.UserFacingException;
import brooklyn.util.javalang.Threads;
import brooklyn.util.stream.Streams;
import brooklyn.util.text.Strings;
import io.airlift.command.Arguments;
import io.airlift.command.Cli;
import io.airlift.command.Cli.CliBuilder;
import io.airlift.command.Command;
import io.airlift.command.Help;
import io.airlift.command.Option;
import io.airlift.command.ParseException;

public class NatMicroServiceMain {

    // See brooklyn.cli.Main for original code; this copies the same structure.
    
    private static final Logger log = LoggerFactory.getLogger(NatMicroServiceMain.class);

    public static final String PROPERTIES_URL = "classpath://endpoints.properties";
    
    // Error codes
    public static final int SUCCESS = 0;
    public static final int PARSE_ERROR = 1;
    public static final int EXECUTION_ERROR = 2;
    public static final int CONFIGURATION_ERROR = 3;

    public static void main(String... args) {
        new NatMicroServiceMain().execCli(args);
    }

    @VisibleForTesting
    public CliBuilder<Callable<?>> cliBuilder() {
        CliBuilder<Callable<?>> builder = Cli.<Callable<?>>builder(cliScriptName())
                .withDescription("Brooklyn Management Service")
                .withDefaultCommand(InfoCommand.class)
                .withCommands(
                        HelpCommand.class,
                        InfoCommand.class,
                        LaunchCommand.class
                );

        return builder;
    }

    protected void execCli(String ...args) {
        execCli(cliBuilder().build(), args);
    }
    
    protected String cliScriptName() {
        return "nat-microservice";
    }

    protected void execCli(Cli<Callable<?>> parser, String ...args) {
        try {
            log.debug("Parsing command line arguments: {}", Arrays.asList(args));
            Callable<?> command = parser.parse(args);
            log.debug("Executing command: {}", command);
            command.call();
            System.exit(SUCCESS);
        } catch (ParseException pe) { // looks like the user typed it wrong
            System.err.println("Parse error: " + pe.getMessage()); // display
                                                                   // error
            System.err.println(getUsageInfo(parser)); // display cli help
            System.exit(PARSE_ERROR);
        } catch (FatalConfigurationRuntimeException e) {
            log.error("Configuration error: "+e.getMessage(), e.getCause());
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(CONFIGURATION_ERROR);
        } catch (FatalRuntimeException e) { // anticipated non-configuration error
            log.error("Startup error: "+e.getMessage(), e.getCause());
            System.err.println("Startup error: "+e.getMessage());
            System.exit(EXECUTION_ERROR);
        } catch (Exception e) { // unexpected error during command execution
            log.error("Execution error: " + e.getMessage(), e);
            System.err.println("Execution error: " + e.getMessage());
            if (!(e instanceof UserFacingException))
                e.printStackTrace();
            System.exit(EXECUTION_ERROR);
        }
    }

    @Command(name = "help", description = "Display help for available commands")
    public static class HelpCommand implements Callable<Void> {

        @Inject
        public Help help;

        @Override
        public Void call() throws Exception {
            if (log.isDebugEnabled()) log.debug("Invoked help command: {}", this);
            return help.call();
        }
    }

    @Command(name = "info", description = "Display information about brooklyn")
    public static class InfoCommand implements Callable<Void> {
        
        /** extra arguments */
        @Arguments
        public List<String> arguments = new ArrayList<String>();
        
        /** @return true iff there are arguments; it also sys.errs a warning in that case  */
        protected boolean warnIfArguments() {
            if (arguments.isEmpty()) return false;
            System.err.println("Invalid subcommand arguments: "+Strings.join(arguments, " "));
            return true;
        }

        @Override
        public Void call() throws Exception {
            warnIfArguments();

            System.out.println("Version:  " + BrooklynVersion.get());
            System.out.println("Website:  http://brooklyn.incubator.apache.org");
            System.out.println("Source:   https://github.com/brooklyncentral/advanced-networking");
            System.out.println();
            System.out.println("Copyright 2015 Cloudsoft Corporation.");
            System.out.println("Licensed under the Apache 2.0 License");
            System.out.println();

            return null;
        }
    }

    @Command(name = "launch", description = "Starts the NAT micro-service")
    public static class LaunchCommand implements Callable<Void> {

        @Option(name = { "--endpointsProperties" }, required = true, title = "local property file",
                description = "local property file, giving the endpoints and trust-store details for each")
        public String endpointProperties;

        @Option(name = { "--publicPortRange" }, required = false, title = "Public port range",
                description = "Range of ports for auto-assigning the public port side (i.e. lookup what is free in that range and pick one automatically)")
        public String publicPortRange;

        @Option(name = { "--bindAddress" }, title = "Bind address",
                description = "Interface(s) to listen on")
        public String bindAddress = null;

        @Option(name = { "--port" }, title = "Port",
                description = "Port to listen on")
        public int port = 8443;

        @Override
        public Void call() throws Exception {
            log.info("Starting NAT micro-service");
            InputStream in = ResourceUtils.create(NatMicroServiceMain.class).getResourceFromUrl(endpointProperties);
            Map<String, EndpointConfig> endpoints;
            try {
                endpoints = PropertiesParser.parseProperties(in);
            } finally {
                Streams.closeQuietly(in);
            }

            NatServiceDispatcher.Builder dispatcherBuilder = NatServiceDispatcher.builder().endpoints(endpoints);
            if (publicPortRange != null) {
                dispatcherBuilder.portRange(PortRanges.fromString(publicPortRange));
            }
            NatServiceDispatcher dispatcher = dispatcherBuilder.build();

            final NatMicroService service = NatMicroService.builder()
                    .dispatcher(dispatcher)
                    .bindAddress(bindAddress)
                    .port(port)
                    .build();
            service.start();
            
            StaticRefs.dispatcher = dispatcher;
            StaticRefs.service = service;

            log.info("Started NAT micro-service at " + service.getRootUrl());
            service.join();
            
            Threads.addShutdownHook(new Runnable() {
                @Override public void run() {
                    if (service != null)
                        try {
                            service.stop();
                        } catch (Exception e) {
                            log.warn("Problem shutting down web-service; continuing", e);
                            throw Exceptions.propagate(e);
                        }
                }});
            
            return null;
        }
    }
    
    @VisibleForTesting
    public static class StaticRefs {
        public static volatile NatServiceDispatcher dispatcher;
        public static volatile NatMicroService service;
    }
    
    protected String getUsageInfo(Cli<Callable<?>> parser) {
        StringBuilder help = new StringBuilder();
        help.append("\n");
        Help.help(parser.getMetadata(), Collections.<String>emptyList(), help);
        return help.toString();
    }
}
