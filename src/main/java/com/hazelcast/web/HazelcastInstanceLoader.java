/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.web;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.XmlClientConfigBuilder;
import com.hazelcast.config.Config;
import com.hazelcast.config.ConfigLoader;
import com.hazelcast.config.InvalidConfigurationException;
import com.hazelcast.config.ListenerConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.UrlXmlConfig;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.DuplicateInstanceNameException;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.util.ExceptionUtil;
import com.hazelcast.web.listener.ClientLifecycleListener;
import com.hazelcast.web.listener.ServerLifecycleListener;

import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import java.util.logging.Level;

import static java.lang.String.format;

final class HazelcastInstanceLoader {

    public static final String INSTANCE_NAME = "instance-name";
    public static final String CONFIG_LOCATION = "config-location";
    public static final String USE_CLIENT = "use-client";
    public static final String CLIENT_CONFIG_LOCATION = "client-config-location";
    public static final String STICKY_SESSION_CONFIG = "sticky-session";
    public static final String SESSION_TTL_CONFIG = "session-ttl-seconds";
    public static final String MAP_NAME = "map-name";

    private static final String SESSION_TTL_DEFAULT_SECONDS = "1800";

    private static final ILogger LOGGER = Logger.getLogger(HazelcastInstanceLoader.class);

    private HazelcastInstanceLoader() {
    }

    public static HazelcastInstance createInstance(final ClusteredSessionService sessionService)
            throws ServletException {

        final Properties properties = sessionService.getProperties();
        final String instanceName = properties.getProperty(INSTANCE_NAME);
        final String configLocation = properties.getProperty(CONFIG_LOCATION);
        final String useClientProp = properties.getProperty(USE_CLIENT);
        final String clientConfigLocation = properties.getProperty(CLIENT_CONFIG_LOCATION);
        final boolean useClient = !isEmpty(useClientProp) && Boolean.parseBoolean(useClientProp);
        URL configUrl = null;
        if (useClient && !isEmpty(clientConfigLocation)) {
            configUrl = getConfigURL(sessionService.getFilterConfig(), clientConfigLocation);
        } else if (!isEmpty(configLocation)) {
            configUrl = getConfigURL(sessionService.getFilterConfig(), configLocation);
        }
        String sessionTTLConfig = properties.getProperty(SESSION_TTL_CONFIG);
        if (useClient) {
            if (sessionTTLConfig != null) {
                throw new InvalidConfigurationException("session-ttl-seconds cannot be used with client/server mode.");
            }
            boolean isSticky = Boolean.valueOf(properties.getProperty(STICKY_SESSION_CONFIG));
            return createClientInstance(sessionService, configUrl, instanceName, isSticky);
        }
        Config config = getServerConfig(properties.getProperty(MAP_NAME), configUrl, sessionTTLConfig);
        return createHazelcastInstance(sessionService, instanceName, config);
    }

    private static Config getServerConfig(String mapName, URL configUrl, String sessionTTLConfig) throws ServletException {
        Config config;
        if (configUrl == null) {
            config = new XmlConfigBuilder().build();
        } else {
            try {
                config = new UrlXmlConfig(configUrl);
            } catch (IOException e) {
                throw new ServletException(e);
            }
        }
        if (sessionTTLConfig == null) {
            sessionTTLConfig = SESSION_TTL_DEFAULT_SECONDS;
        }
        MapConfig mapConfig = config.getMapConfig(mapName);
        try {
            mapConfig.setMaxIdleSeconds(Integer.parseInt(sessionTTLConfig));
        } catch (NumberFormatException e) {
            ExceptionUtil.rethrow(new InvalidConfigurationException("session-ttl-seconds must be a numeric value"));
        }
        return config;
    }

    private static HazelcastInstance createHazelcastInstance(ClusteredSessionService sessionService,
                                                             String instanceName, Config config) {
        ListenerConfig listenerConfig = new ListenerConfig(new ServerLifecycleListener(sessionService));
        config.addListenerConfig(listenerConfig);
        if (!isEmpty(instanceName)) {
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info(format("getOrCreateHazelcastInstance for session replication, using name '%s'", instanceName));
            }
            config.setInstanceName(instanceName);
            return Hazelcast.getOrCreateHazelcastInstance(config);
        } else {
            LOGGER.info("Creating a new HazelcastInstance for session replication");
            return Hazelcast.newHazelcastInstance(config);
        }
    }

    private static HazelcastInstance createClientInstance(ClusteredSessionService sessionService,
                                                          URL configUrl, String instanceName,
                                                          boolean isSticky) throws ServletException {
        LOGGER.warning("Creating HazelcastClient for session replication...");
        LOGGER.warning("make sure this client has access to an already running cluster...");
        ClientConfig clientConfig;
        if (configUrl == null) {
            clientConfig = new ClientConfig();
        } else {
            try {
                clientConfig = new XmlClientConfigBuilder(configUrl).build();
            } catch (IOException e) {
                throw new ServletException(e);
            }
        }
        if (isSticky) {
            clientConfig.getNetworkConfig().setConnectionAttemptLimit(1);
        }
        ListenerConfig listenerConfig = new ListenerConfig(new ClientLifecycleListener(sessionService));
        clientConfig.addListenerConfig(listenerConfig);

        if (!isEmpty(instanceName)) {
            HazelcastInstance instance = HazelcastClient.getHazelcastClientByName(instanceName);
            if (instance != null) {
                return instance;
            }
            clientConfig.setInstanceName(instanceName);
            try {
                return HazelcastClient.newHazelcastClient(clientConfig);
            } catch (DuplicateInstanceNameException e) {
                return HazelcastClient.getHazelcastClientByName(instanceName);
            }
        }
        return HazelcastClient.newHazelcastClient(clientConfig);
    }

    private static URL getConfigURL(final FilterConfig filterConfig, final String configLocation) throws ServletException {
        URL configUrl = null;
        try {
            configUrl = filterConfig.getServletContext().getResource(configLocation);
        } catch (MalformedURLException ignore) {
            LOGGER.info("ignored MalformedURLException");
        }
        if (configUrl == null) {
            configUrl = ConfigLoader.locateConfig(configLocation);
        }
        if (configUrl == null) {
            throw new ServletException("Could not load configuration '" + configLocation + "'");
        }
        return configUrl;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().length() == 0;
    }
}
