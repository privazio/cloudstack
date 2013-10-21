// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.agent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import javax.naming.ConfigurationException;

import org.apache.commons.daemon.Daemon;
import org.apache.commons.daemon.DaemonContext;
import org.apache.commons.daemon.DaemonInitException;
import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;

import com.cloud.agent.Agent.ExitStatus;
import com.cloud.agent.dao.StorageComponent;
import com.cloud.agent.dao.impl.PropertiesStorage;
import com.cloud.resource.ServerResource;
import com.cloud.utils.LogUtils;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.ProcessUtil;
import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.backoff.BackoffAlgorithm;
import com.cloud.utils.backoff.impl.ConstantTimeBackoff;
import com.cloud.utils.exception.CloudRuntimeException;

public class AgentShell implements IAgentShell, Daemon {
    private static final Logger s_logger = Logger.getLogger(AgentShell.class
            .getName());

    private final Properties _properties = new Properties();
    private final Map<String, Object> _cmdLineProperties = new HashMap<String, Object>();
    private StorageComponent _storage;
    private BackoffAlgorithm _backoff;
    private String _version;
    private String _zone;
    private String _pod;
    private String _host;
    private String _privateIp;
    private int _port;
    private int _proxyPort;
    private int _workers;
    private String _guid;
    private int _nextAgentId = 1;
    private volatile boolean _exit = false;
    private int _pingRetries;
    private final List<Agent> _agents = new ArrayList<Agent>();


    public AgentShell() {
    }

    @Override
    public Properties getProperties() {
        return _properties;
    }

    @Override
    public BackoffAlgorithm getBackoffAlgorithm() {
        return _backoff;
    }

    @Override
    public int getPingRetries() {
        return _pingRetries;
    }

    @Override
    public String getVersion() {
        return _version;
    }

    @Override
    public String getZone() {
        return _zone;
    }

    @Override
    public String getPod() {
        return _pod;
    }

    @Override
    public String getHost() {
        return _host;
    }

    @Override
    public String getPrivateIp() {
        return _privateIp;
    }

    @Override
    public int getPort() {
        return _port;
    }

    @Override
    public int getProxyPort() {
        return _proxyPort;
    }

    @Override
    public int getWorkers() {
        return _workers;
    }

    @Override
    public String getGuid() {
        return _guid;
    }

    @Override
    public Map<String, Object> getCmdLineProperties() {
        return _cmdLineProperties;
    }

    public String getProperty(String prefix, String name) {
        if (prefix != null)
            return _properties.getProperty(prefix + "." + name);

        return _properties.getProperty(name);
    }

    @Override
    public String getPersistentProperty(String prefix, String name) {
        if (prefix != null)
            return _storage.get(prefix + "." + name);
        return _storage.get(name);
    }

    @Override
    public void setPersistentProperty(String prefix, String name, String value) {
        if (prefix != null)
            _storage.persist(prefix + "." + name, value);
        else
            _storage.persist(name, value);
    }

    private void loadProperties() throws ConfigurationException {
        final File file = PropertiesUtil.findConfigFile("agent.properties");
        if (file == null) {
            throw new ConfigurationException("Unable to find agent.properties.");
        }

        s_logger.info("agent.properties found at " + file.getAbsolutePath());

        try {
            _properties.load(new FileInputStream(file));
        } catch (final FileNotFoundException ex) {
            throw new CloudRuntimeException("Cannot find the file: "
                    + file.getAbsolutePath(), ex);
        } catch (final IOException ex) {
            throw new CloudRuntimeException("IOException in reading "
                    + file.getAbsolutePath(), ex);
        }
    }

    protected boolean parseCommand(final String[] args)
            throws ConfigurationException {
        String host = null;
        String workers = null;
        String port = null;
        String zone = null;
        String pod = null;
        String guid = null;
        for (int i = 0; i < args.length; i++) {
            final String[] tokens = args[i].split("=");
            if (tokens.length != 2) {
                System.out.println("Invalid Parameter: " + args[i]);
                continue;
            }

            // save command line properties
            _cmdLineProperties.put(tokens[0], tokens[1]);

            if (tokens[0].equalsIgnoreCase("port")) {
                port = tokens[1];
            } else if (tokens[0].equalsIgnoreCase("threads") || tokens[0].equalsIgnoreCase("workers")) {
                workers = tokens[1];
            } else if (tokens[0].equalsIgnoreCase("host")) {
                host = tokens[1];
            } else if (tokens[0].equalsIgnoreCase("zone")) {
                zone = tokens[1];
            } else if (tokens[0].equalsIgnoreCase("pod")) {
                pod = tokens[1];
            } else if (tokens[0].equalsIgnoreCase("guid")) {
                guid = tokens[1];
            } else if (tokens[0].equalsIgnoreCase("eth1ip")) {
                _privateIp = tokens[1];
            }
        }

        if (port == null) {
            port = getProperty(null, "port");
        }

        _port = NumbersUtil.parseInt(port, 8250);

        _proxyPort = NumbersUtil.parseInt(
                getProperty(null, "consoleproxy.httpListenPort"), 443);

        if (workers == null) {
            workers = getProperty(null, "workers");
        }

        _workers = NumbersUtil.parseInt(workers, 5);

        if (host == null) {
            host = getProperty(null, "host");
        }

        if (host == null) {
            host = "localhost";
        }
        _host = host;

        if (zone != null)
            _zone = zone;
        else
            _zone = getProperty(null, "zone");
        if (_zone == null || (_zone.startsWith("@") && _zone.endsWith("@"))) {
            _zone = "default";
        }

        if (pod != null)
            _pod = pod;
        else
            _pod = getProperty(null, "pod");
        if (_pod == null || (_pod.startsWith("@") && _pod.endsWith("@"))) {
            _pod = "default";
        }

        if (_host == null || (_host.startsWith("@") && _host.endsWith("@"))) {
            throw new ConfigurationException(
                    "Host is not configured correctly: " + _host);
        }

        final String retries = getProperty(null, "ping.retries");
        _pingRetries = NumbersUtil.parseInt(retries, 5);

        String value = getProperty(null, "developer");
        boolean developer = Boolean.parseBoolean(value);

        if (guid != null)
            _guid = guid;
        else
            _guid = getProperty(null, "guid");
        if (_guid == null) {
            if (!developer) {
                throw new ConfigurationException("Unable to find the guid");
            }
            _guid = UUID.randomUUID().toString();
            _properties.setProperty("guid", _guid);
        }

        return true;
    }
    
    @Override
    public void init(DaemonContext dc) throws DaemonInitException {
        s_logger.debug("Initializing AgentShell from JSVC");
        try {
            init(dc.getArguments());
        } catch (ConfigurationException ex) {
            throw new DaemonInitException("Initialization failed", ex);
        }
    }
    
    public void init(String[] args) throws ConfigurationException {

    	// PropertiesUtil is used both in management server and agent packages,
    	// it searches path under class path and common J2EE containers
    	// For KVM agent, do it specially here
    	
    	File file = new File("/etc/cloudstack/agent/log4j-cloud.xml");
    	if(file == null || !file.exists()) {
    		file = PropertiesUtil.findConfigFile("log4j-cloud.xml");
    	}
    	DOMConfigurator.configureAndWatch(file.getAbsolutePath());

    	s_logger.info("Agent started");
    	
        final Class<?> c = this.getClass();
        _version = c.getPackage().getImplementationVersion();
        if (_version == null) {
            throw new CloudRuntimeException(
                    "Unable to find the implementation version of this agent");
        }
        s_logger.info("Implementation Version is " + _version);

        loadProperties();
        parseCommand(args);

        if (s_logger.isDebugEnabled()) {
            List<String> properties = Collections.list((Enumeration<String>)_properties.propertyNames());
            for (String property:properties){
                s_logger.debug("Found property: " + property);
            }
        }
            
        s_logger.info("Defaulting to using properties file for storage");
        _storage = new PropertiesStorage();
        _storage.configure("Storage", new HashMap<String, Object>());

        // merge with properties from command line to let resource access
        // command line parameters
        for (Map.Entry<String, Object> cmdLineProp : getCmdLineProperties()
                .entrySet()) {
            _properties.put(cmdLineProp.getKey(), cmdLineProp.getValue());
        }

        s_logger.info("Defaulting to the constant time backoff algorithm");
        _backoff = new ConstantTimeBackoff();
        _backoff.configure("ConstantTimeBackoff", new HashMap<String, Object>());
    }

    private void launchAgent() throws ConfigurationException {
        String resourceClassNames = getProperty(null, "resource");
        s_logger.trace("resource=" + resourceClassNames);
        if (resourceClassNames != null) {
            launchAgentFromClassInfo(resourceClassNames);
            return;
        }

        launchAgentFromTypeInfo();
    }

    private void launchAgentFromClassInfo(String resourceClassNames)
            throws ConfigurationException {
        String[] names = resourceClassNames.split("\\|");
        for (String name : names) {
            Class<?> impl;
            try {
                impl = Class.forName(name);
                final Constructor<?> constructor = impl
                        .getDeclaredConstructor();
                constructor.setAccessible(true);
                ServerResource resource = (ServerResource) constructor
                        .newInstance();
                launchAgent(getNextAgentId(), resource);
            } catch (final ClassNotFoundException e) {
                throw new ConfigurationException("Resource class not found: "
                        + name + " due to: " + e.toString());
            } catch (final SecurityException e) {
                throw new ConfigurationException(
                        "Security excetion when loading resource: " + name
                        + " due to: " + e.toString());
            } catch (final NoSuchMethodException e) {
                throw new ConfigurationException(
                        "Method not found excetion when loading resource: "
                                + name + " due to: " + e.toString());
            } catch (final IllegalArgumentException e) {
                throw new ConfigurationException(
                        "Illegal argument excetion when loading resource: "
                                + name + " due to: " + e.toString());
            } catch (final InstantiationException e) {
                throw new ConfigurationException(
                        "Instantiation excetion when loading resource: " + name
                        + " due to: " + e.toString());
            } catch (final IllegalAccessException e) {
                throw new ConfigurationException(
                        "Illegal access exception when loading resource: "
                                + name + " due to: " + e.toString());
            } catch (final InvocationTargetException e) {
                throw new ConfigurationException(
                        "Invocation target exception when loading resource: "
                                + name + " due to: " + e.toString());
            }
        }
    }

    private void launchAgentFromTypeInfo() throws ConfigurationException {
        String typeInfo = getProperty(null, "type");
        if (typeInfo == null) {
            s_logger.error("Unable to retrieve the type");
            throw new ConfigurationException(
                    "Unable to retrieve the type of this agent.");
        }
        s_logger.trace("Launching agent based on type=" + typeInfo);
    }

    private void launchAgent(int localAgentId, ServerResource resource)
            throws ConfigurationException {
        // we don't track agent after it is launched for now
        Agent agent = new Agent(this, localAgentId, resource);
        _agents.add(agent);
        agent.start();
    }

    public synchronized int getNextAgentId() {
        return _nextAgentId++;
    }

    public void start() {
        try {
            /* By default we only search for log4j.xml */
            LogUtils.initLog4j("log4j-cloud.xml");

            System.setProperty("java.net.preferIPv4Stack", "true");

            String instance = getProperty(null, "instance");
            if (instance == null) {
                if (Boolean.parseBoolean(getProperty(null, "developer"))) {
                    instance = UUID.randomUUID().toString();
                } else {
                    instance = "";
                }
            } else {
                instance += ".";
            }

            String pidDir = getProperty(null, "piddir");

            final String run = "agent." + instance + "pid";
            s_logger.debug("Checking to see if " + run + " exists.");
            ProcessUtil.pidCheck(pidDir, run);

            launchAgent();

            try {
                while (!_exit)
                    Thread.sleep(1000);
            } catch (InterruptedException e) {
            }

        } catch (final ConfigurationException e) {
            s_logger.error("Unable to start agent: " + e.getMessage());
            System.out.println("Unable to start agent: " + e.getMessage());
            System.exit(ExitStatus.Configuration.value());
        } catch (final Exception e) {
            s_logger.error("Unable to start agent: ", e);
            System.out.println("Unable to start agent: " + e.getMessage());
            System.exit(ExitStatus.Error.value());
        }
    }

    public void stop() {
        _exit = true;
    }

    public void destroy() {

    }

    public static void main(String[] args) {
        try {
            s_logger.debug("Initializing AgentShell from main");
            AgentShell shell = new AgentShell();
            shell.init(args);
            shell.start();
        } catch (ConfigurationException e) {
            System.out.println(e.getMessage());
        }
    }

}
