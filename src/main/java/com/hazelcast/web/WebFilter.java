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

import com.hazelcast.config.MapConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.util.UuidUtil;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

import static com.hazelcast.web.HazelcastInstanceLoader.CLIENT_CONFIG_LOCATION;
import static com.hazelcast.web.HazelcastInstanceLoader.CONFIG_LOCATION;
import static com.hazelcast.web.HazelcastInstanceLoader.INSTANCE_NAME;
import static com.hazelcast.web.HazelcastInstanceLoader.MAP_NAME;
import static com.hazelcast.web.HazelcastInstanceLoader.SESSION_TTL_CONFIG;
import static com.hazelcast.web.HazelcastInstanceLoader.STICKY_SESSION_CONFIG;
import static com.hazelcast.web.HazelcastInstanceLoader.USE_CLIENT;

/**
 * Provides clustered sessions by backing session data with an {@link IMap}.
 * <p/>
 * Using this filter requires also registering a {@link SessionListener} to provide session timeout notifications.
 * Failure to register the listener when using this filter will result in session state getting out of sync between
 * the servlet container and Hazelcast.
 * <p/>
 * This filter supports the following {@code &lt;init-param&gt;} values:
 * <ul>
 * <li>{@code use-client}: When enabled, a {@link com.hazelcast.client.HazelcastClient HazelcastClient} is
 * used to connect to the cluster, rather than joining as a full node. (Default: {@code false})</li>
 * <li>{@code config-location}: Specifies the location of an XML configuration file that can be used to
 * initialize the {@link HazelcastInstance} (Default: None; the {@link HazelcastInstance} is initialized
 * using its own defaults)</li>
 * <li>{@code client-config-location}: Specifies the location of an XML configuration file that can be
 * used to initialize the {@link HazelcastInstance}. <i>This setting is only checked when {@code use-client}
 * is set to {@code true}.</i> (Default: Falls back on {@code config-location})</li>
 * <li>{@code instance-name}: Names the {@link HazelcastInstance}. This can be used to reference an already-
 * initialized {@link HazelcastInstance} in the same JVM (Default: The configured instance name, or a
 * generated name if the configuration does not specify a value)</li>
 * <li>{@code shutdown-on-destroy}: When enabled, shuts down the {@link HazelcastInstance} when the filter is
 * destroyed (Default: {@code true})</li>
 * <li>{@code map-name}: Names the {@link IMap} the filter should use to persist session details (Default:
 * {@code "_web_" + ServletContext.getServletContextName()}; e.g. "_web_MyApp")</li>
 *  * <li>{@code session-ttl-seconds}: Sets the {@link MapConfig#setMaxIdleSeconds(int)} (int) time-to-live} for
 * the {@link IMap} used to persist session details (Default: Uses the existing {@link MapConfig} setting
 * for the {@link IMap}, which defaults to infinite)</li>
 * <li>{@code sticky-session}: When enabled, optimizes {@link IMap} interactions by assuming individual sessions
 * are only used from a single node (Default: {@code true})</li>
 * <li>{@code deferred-write}: When enabled, optimizes {@link IMap} interactions by only writing session attributes
 * at the end of a request. This can yield significant performance improvements for session-heavy applications
 * (Default: {@code false})</li>
 * <li>{@code cookie-name}: Sets the name for the Hazelcast session cookie (Default:
 * {@link #HAZELCAST_SESSION_COOKIE_NAME "hazelcast.sessionId"}</li>
 * <li>{@code cookie-domain}: Sets the domain for the Hazelcast session cookie (Default: {@code null})</li>
 * <li>{@code cookie-secure}: When enabled, indicates the Hazelcast session cookie should only be sent over
 * secure protocols (Default: {@code false})</li>
 * <li>{@code cookie-http-only}: When enabled, marks the Hazelcast session cookie as "HttpOnly", indicating
 * it should not be available to scripts (Default: {@code false})
 * <ul>
 * <li>{@code cookie-http-only} requires a Servlet 3.0-compatible container, such as Tomcat 7+ or Jetty 8+</li>
 * </ul>
 * </li>
 * </ul>
 */
public class WebFilter implements Filter {

    /**
     * This is prefix for hazelcast session attributes
     */
    public static final String WEB_FILTER_ATTRIBUTE_KEY = WebFilter.class.getName();

    protected static final ILogger LOGGER = Logger.getLogger(WebFilter.class);
    protected static final LocalCacheEntry NULL_ENTRY = new LocalCacheEntry(false);
    protected static final String HAZELCAST_SESSION_COOKIE_NAME = "hazelcast.sessionId";

    protected ServletContext servletContext;
    protected FilterConfig filterConfig;

    private final ConcurrentMap<String, String> originalSessions = new ConcurrentHashMap<String, String>(1000);
    private final ConcurrentMap<String, HazelcastHttpSession> sessions =
            new ConcurrentHashMap<String, HazelcastHttpSession>(1000);

    private String sessionCookieName = HAZELCAST_SESSION_COOKIE_NAME;
    private String sessionCookieDomain;
    private String sessionCookiePath;
    private boolean sessionCookieSecure;
    private boolean sessionCookieHttpOnly;
    private boolean stickySession = true;
    private boolean shutdownOnDestroy = true;
    private boolean deferredWrite;
    private boolean useRequestParameter;
    private Properties properties;
    private ClusteredSessionService clusteredSessionService;

    public WebFilter() {
    }

    public WebFilter(Properties properties) {
        this.properties = properties;
    }

    public Properties getProperties() {
        return properties;
    }

    void destroyOriginalSession(HttpSession originalSession) {
        String hazelcastSessionId = originalSessions.remove(originalSession.getId());
        if (hazelcastSessionId != null) {
            HazelcastHttpSession hazelSession = sessions.get(hazelcastSessionId);
            if (hazelSession != null) {
                destroySession(hazelSession, false);
            }
        }
    }

    public ClusteredSessionService getClusteredSessionService() {
        return clusteredSessionService;
    }

    private static String generateSessionId() {
        String id = UuidUtil.newSecureUuidString();
        StringBuilder sb = new StringBuilder("HZ");
        char[] chars = id.toCharArray();
        for (final char c : chars) {
            if (c != '-') {
                if (Character.isLetter(c)) {
                    sb.append(Character.toUpperCase(c));
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    @Override
    public final void init(final FilterConfig config)
            throws ServletException {
        filterConfig = config;
        // Register the WebFilter with the ServletContext so SessionListener can look it up. The name
        // here is WebFilter.class instead of getClass() because WebFilter can have subclasses
        servletContext = config.getServletContext();
        servletContext.setAttribute(WEB_FILTER_ATTRIBUTE_KEY, this);
        loadProperties();
        initCookieParams();
        initParams();
        clusteredSessionService = new ClusteredSessionService(filterConfig, properties);

        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("sticky:" + stickySession + ", shutdown-on-destroy: " + shutdownOnDestroy
                    + ", map-name: " + properties.getProperty(MAP_NAME));
        }
    }

    private void initParams() {
        String stickySessionParam = getParam("sticky-session");
        if (stickySessionParam != null) {
            stickySession = Boolean.valueOf(stickySessionParam);
        }
        String shutdownOnDestroyParam = getParam("shutdown-on-destroy");
        if (shutdownOnDestroyParam != null) {
            shutdownOnDestroy = Boolean.valueOf(shutdownOnDestroyParam);
        }
        String deferredWriteParam = getParam("deferred-write");
        if (deferredWriteParam != null) {
            deferredWrite = Boolean.parseBoolean(deferredWriteParam);
        }
        String useRequestParameterParam = getParam("use-request-parameter");
        if (useRequestParameterParam != null) {
            useRequestParameter = Boolean.parseBoolean(useRequestParameterParam);
        }
    }

    private void initCookieParams() {
        String cookieName = getParam("cookie-name");
        if (cookieName != null) {
            sessionCookieName = cookieName;
        }
        String cookieDomain = getParam("cookie-domain");
        if (cookieDomain != null) {
            sessionCookieDomain = cookieDomain;
        }
        String cookieSecure = getParam("cookie-secure");
        if (cookieSecure != null) {
            sessionCookieSecure = Boolean.valueOf(cookieSecure);
        }
        String cookieHttpOnly = getParam("cookie-http-only");
        if (cookieHttpOnly != null) {
            sessionCookieHttpOnly = Boolean.valueOf(cookieHttpOnly);
        }
        String cookiePath = getParam("cookie-path");
        if (cookiePath != null) {
            sessionCookiePath = cookiePath;
        }
    }

    private void loadProperties() throws ServletException {
        if (properties == null) {
            properties = new Properties();
        }
        setProperty(CONFIG_LOCATION);
        setProperty(INSTANCE_NAME);
        setProperty(USE_CLIENT);
        setProperty(CLIENT_CONFIG_LOCATION);
        setProperty(STICKY_SESSION_CONFIG);
        setProperty(SESSION_TTL_CONFIG);

        String mapName = getParam(MAP_NAME);
        if (mapName == null) {
            mapName = "_web_" + servletContext.getServletContextName();
        }
        properties.setProperty(MAP_NAME, mapName);
    }

    private void setProperty(String propertyName) {
        String value = getParam(propertyName);
        if (value != null) {
            properties.setProperty(propertyName, value);
        }
    }

    private boolean sessionExistsInTheCluster(String hazelcastSessionId) {
        try {
            return hazelcastSessionId != null && clusteredSessionService.containsSession(hazelcastSessionId);
        } catch (Exception ignored) {
            return false;
        }
    }

    protected HazelcastHttpSession createNewSession(HazelcastRequestWrapper requestWrapper, String existingSessionId) {
        // use existing hazelcast session id for the new session only if the session info exists in the cluster
        String id = sessionExistsInTheCluster(existingSessionId) ? existingSessionId : generateSessionId();

        if (requestWrapper.getOriginalSession(false) != null) {
            LOGGER.finest("Original session exists!!!");
        }
        HttpSession originalSession = requestWrapper.getOriginalSession(true);
        HazelcastHttpSession hazelcastSession = createHazelcastHttpSession(id, originalSession, deferredWrite);
        if (existingSessionId == null) {
            hazelcastSession.setClusterWideNew(true);
            // If the session is being created for the first time, add its initial reference in the cluster-wide map.
        }
        updateSessionMaps(id, originalSession, hazelcastSession);
        addSessionCookie(requestWrapper, id);
        return hazelcastSession;
    }

    /**
     * {@code HazelcastHttpSession instance} creation is split off to a separate method to allow subclasses to return a
     * customized / extended version of {@code HazelcastHttpSession}.
     *
     * @param id              the session id
     * @param originalSession the original session
     * @param deferredWrite   whether writes are deferred
     * @return a new HazelcastHttpSession instance
     */
    protected HazelcastHttpSession createHazelcastHttpSession(String id, HttpSession originalSession, boolean deferredWrite) {
        return new HazelcastHttpSession(this, id, originalSession, deferredWrite, stickySession);
    }

    private void updateSessionMaps(String sessionId, HttpSession originalSession, HazelcastHttpSession hazelcastSession) {
        sessions.put(hazelcastSession.getId(), hazelcastSession);
        String oldHazelcastSessionId = originalSessions.put(originalSession.getId(), hazelcastSession.getId());
        if (LOGGER.isFinestEnabled()) {
            if (oldHazelcastSessionId != null) {
                LOGGER.finest("!!! Overwrote an existing hazelcastSessionId " + oldHazelcastSessionId);
            }
            LOGGER.finest("Created new session with id: " + sessionId);
            LOGGER.finest(sessions.size() + " is sessions.size and originalSessions.size: " + originalSessions.size());
        }
    }

    /**
     * Destroys a session, determining if it should be destroyed clusterwide automatically or via expiry.
     *
     * @param session    the session to be destroyed <i>locally</i>
     * @param invalidate {@code true} if the session has been invalidated and should be destroyed on all nodes
     *                   in the cluster; otherwise, {@code false} to only remove the session globally if this
     *                   node was the final node referencing it
     */
    protected void destroySession(HazelcastHttpSession session, boolean invalidate) {
        if (LOGGER.isFinestEnabled()) {
            LOGGER.finest("Destroying local session: " + session.getId());
        }
        sessions.remove(session.getId());
        originalSessions.remove(session.getOriginalSession().getId());
        session.destroy(invalidate);
    }

    private HazelcastHttpSession getSessionWithId(final String sessionId) {
        HazelcastHttpSession session = sessions.get(sessionId);
        if (session != null && !session.isValid()) {
            destroySession(session, true);
            session = null;
        }
        return session;
    }

    private void addSessionCookie(final HazelcastRequestWrapper req, final String sessionId) {
        final Cookie sessionCookie = new Cookie(sessionCookieName, sessionId);

        // Changes Added to take the session path from Init Parameter if passed
        // Context Path will be used as Session Path if the Init Param is not passed to keep it backward compatible
        String path = null;

        if (null != sessionCookiePath && !sessionCookiePath.isEmpty()) {
            path = sessionCookiePath;
        } else {
            path = req.getContextPath();
        }

        if ("".equals(path)) {
            path = "/";
        }
        sessionCookie.setPath(path);
        sessionCookie.setMaxAge(-1);
        if (sessionCookieDomain != null) {
            sessionCookie.setDomain(sessionCookieDomain);
        }
        if (sessionCookieHttpOnly) {
            try {
                sessionCookie.setHttpOnly(true);
            } catch (NoSuchMethodError e) {
                LOGGER.info("HttpOnly cookies require a Servlet 3.0+ container. Add the following to the "
                        + getClass().getName() + " mapping in web.xml to disable HttpOnly cookies:\n"
                        + "<init-param>\n"
                        + "    <param-name>cookie-http-only</param-name>\n"
                        + "    <param-value>false</param-value>\n"
                        + "</init-param>");
            }
        }
        sessionCookie.setSecure(sessionCookieSecure);
        req.res.addCookie(sessionCookie);
    }

    @Override
    public final void doFilter(ServletRequest req, ServletResponse res, final FilterChain chain)
            throws IOException, ServletException {

        HazelcastRequestWrapper requestWrapper =
                new HazelcastRequestWrapper((HttpServletRequest) req, (HttpServletResponse) res);

        chain.doFilter(requestWrapper, res);

        HazelcastHttpSession session = requestWrapper.getSession(false);
        if (session != null && session.isValid() && deferredWrite) {
            if (LOGGER.isFinestEnabled()) {
                LOGGER.finest("UPDATING SESSION " + session.getId());
            }
            session.sessionDeferredWrite();
        }
    }

    @Override
    public final void destroy() {
        sessions.clear();
        originalSessions.clear();
        if (shutdownOnDestroy) {
            clusteredSessionService.destroy();
        }
    }

    String getParam(String name) {
        if (properties != null && properties.containsKey(name)) {
            return properties.getProperty(name);
        } else {
            return filterConfig.getInitParameter(name);
        }
    }

    protected class HazelcastRequestWrapper extends HttpServletRequestWrapper {
        final HttpServletResponse res;
        HazelcastHttpSession hazelcastSession;

        public HazelcastRequestWrapper(final HttpServletRequest req,
                                       final HttpServletResponse res) {
            super(req);
            this.res = res;
        }

        HttpSession getOriginalSession(boolean create) {
            // Find the top non-wrapped Http Servlet request
            HttpServletRequest req = (HttpServletRequest) getRequest();
            while (req instanceof HttpServletRequestWrapper) {
                req = (HttpServletRequest) ((HttpServletRequestWrapper) req).getRequest();
            }
            if (req != null) {
                return req.getSession(create);
            } else {
                return super.getSession(create);
            }
        }

        @Override
        public RequestDispatcher getRequestDispatcher(final String path) {
            final ServletRequest original = getRequest();
            return new RequestDispatcher() {
                public void forward(ServletRequest servletRequest, ServletResponse servletResponse)
                        throws ServletException, IOException {
                    original.getRequestDispatcher(path).forward(servletRequest, servletResponse);
                }

                public void include(ServletRequest servletRequest, ServletResponse servletResponse)
                        throws ServletException, IOException {
                    original.getRequestDispatcher(path).include(servletRequest, servletResponse);
                }
            };
        }

        @Override
        public HttpSession getSession() {
            return getSession(true);
        }

        @Override
        public HazelcastHttpSession getSession(final boolean create) {
            hazelcastSession = readSessionFromLocal();
            String hazelcastSessionId = findHazelcastSessionIdFromRequest();
            if (hazelcastSession == null && !res.isCommitted() && (create || hazelcastSessionId != null)) {
                hazelcastSession = createNewSession(HazelcastRequestWrapper.this, hazelcastSessionId);
            }
            return hazelcastSession;
        }

        private HazelcastHttpSession readSessionFromLocal() {
            String invalidatedOriginalSessionId = null;
            if (hazelcastSession != null && !hazelcastSession.isValid()) {
                LOGGER.finest("Session is invalid!");
                destroySession(hazelcastSession, true);
                invalidatedOriginalSessionId = hazelcastSession.invalidatedOriginalSessionId;
                hazelcastSession = null;
            } else if (hazelcastSession != null) {
                return hazelcastSession;
            }
            HttpSession originalSession = getOriginalSession(false);
            if (originalSession != null) {
                String hazelcastSessionId = originalSessions.get(originalSession.getId());
                if (hazelcastSessionId != null) {
                    hazelcastSession = getSessionWithId(hazelcastSessionId);

                    if (hazelcastSession != null && !hazelcastSession.isStickySession()) {
                        hazelcastSession.updateReloadFlag();
                    }
                    return hazelcastSession;
                }
                // Even though session can be taken from request, it might be already invalidated.
                // For example, in Wildfly (uses Undertow), taken wrapper session might be valid
                // but its underlying real session might be already invalidated after redirection
                // due to its request/url based wrapper session (points to same original session) design.
                // Therefore, we check the taken session id and
                // ignore its invalidation if it is already invalidated inside Hazelcast's session.
                // See issue on Wildfly https://github.com/hazelcast/hazelcast/issues/6335
                if (!originalSession.getId().equals(invalidatedOriginalSessionId)) {
                    originalSession.invalidate();
                }
            }
            return readFromCookie();
        }

        private HazelcastHttpSession readFromCookie() {
            String existingHazelcastSessionId = findHazelcastSessionIdFromRequest();
            if (existingHazelcastSessionId != null) {
                hazelcastSession = getSessionWithId(existingHazelcastSessionId);
                if (hazelcastSession != null && !hazelcastSession.isStickySession()) {
                    hazelcastSession.updateReloadFlag();
                    return hazelcastSession;
                }
            }
            return null;
        }


        private String findHazelcastSessionIdFromRequest() {
            String hzSessionId = null;

            final Cookie[] cookies = getCookies();
            if (cookies != null) {
                for (final Cookie cookie : cookies) {
                    final String name = cookie.getName();
                    final String value = cookie.getValue();
                    if (name.equalsIgnoreCase(sessionCookieName)) {
                        hzSessionId = value;
                        break;
                    }
                }
            }
            // if hazelcast session id is not found on the cookie and using request parameter is enabled, look into
            // request parameters
            if (hzSessionId == null && useRequestParameter) {
                hzSessionId = getParameter(HAZELCAST_SESSION_COOKIE_NAME);
            }

            return hzSessionId;
        }
    }
}
