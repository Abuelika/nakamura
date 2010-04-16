/*
 * Licensed to the Sakai Foundation (SF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.sakaiproject.nakamura.auth.ldap;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.PropertyOption;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.commons.auth.Authenticator;
import org.apache.sling.commons.auth.spi.AuthenticationFeedbackHandler;
import org.apache.sling.commons.auth.spi.AuthenticationHandler;
import org.apache.sling.commons.auth.spi.AuthenticationInfo;
import org.apache.sling.commons.auth.spi.DefaultAuthenticationFeedbackHandler;
import org.apache.sling.commons.osgi.OsgiUtil;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Credentials;
import javax.jcr.SimpleCredentials;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Dictionary;

/**
 * Authentication handler for trusted authentication sources. These sources will
 * authenticate users externally and eventually pass through this handler to establish a
 * trusted relationship continuing into the container.
 */
@Component(metatype = true)
@Service
public class LdapAuthenticationHandler implements AuthenticationHandler,
    AuthenticationFeedbackHandler {
  public static final String LDAP_AUTH = "ldap";

  /**
   * The request parameter causing a 401/UNAUTHORIZED status to be sent back in the {@link
   * #authenticate(HttpServletRequest, HttpServletResponse)} method if no credentials are
   * present in the request (value is "sling:authRequestLogin").
   *
   * @see #requestCredentials(HttpServletRequest, HttpServletResponse)
   */
  static final String REQUEST_LOGIN_PARAMETER = "sling:authRequestLogin";

  /** The name of the parameter providing the login form URL. */
  @Property(value = LdapAuthenticationServlet.SERVLET_PATH)
  static final String PAR_LOGIN_FORM = "sakai.auth.ldap.login.form";

  /**
   * The default Cookie or session attribute name
   *
   * @see #PAR_AUTH_NAME
   */
  static final String DEFAULT_AUTH_NAME = "sakai.ldapauth";

  /**
   * The value of the {@link #PAR_AUTH_STORAGE} parameter indicating the use of a Cookie
   * to store the authentication data.
   */
  static final String AUTH_STORAGE_COOKIE = "cookie";

  /**
   * The value of the {@link #PAR_AUTH_STORAGE} parameter indicating the use of a session
   * attribute to store the authentication data.
   */
  static final String AUTH_STORAGE_SESSION_ATTRIBUTE = "session";

  /**
   * To be used to determine if the auth has value comes from a cookie or from a session
   * attribute.
   */
  static final String DEFAULT_AUTH_STORAGE = AUTH_STORAGE_COOKIE;

  /** Options for auth storage values */
  @Property(value = DEFAULT_AUTH_STORAGE,
      options = {@PropertyOption(name = "cooke", value = "Cookie"),
          @PropertyOption(name = "session", value = "Session Attribute")})
  static final String PAR_AUTH_STORAGE = "sakai.auth.ldap.storage";

  /**
   * The name of the configuration parameter providing the Cookie or session attribute
   * name.
   */
  @Property(value = DEFAULT_AUTH_NAME)
  static final String PAR_AUTH_NAME = "sakai.auth.ldap.param.name";

  /** Default value for the {@link #PAR_CREDENTIALS_ATTRIBUTE_NAME} property */
  static final String DEFAULT_CREDENTIALS_ATTRIBUTE_NAME = DEFAULT_AUTH_NAME;
  
  /**
   * This is the name of the SimpleCredentials attribute that holds the auth info
   * extracted from the cookie value.
   */
  @Property(value = DEFAULT_CREDENTIALS_ATTRIBUTE_NAME)
  static final String PAR_CREDENTIALS_ATTRIBUTE_NAME = "sakai.auth.ldap.credentials.name";

  /**
   * The default authentication data time out value.
   *
   * @see #PAR_AUTH_TIMEOUT
   */
  static final int DEFAULT_AUTH_TIMEOUT = 30;

  /**
   * The number of minutes after which a login session times out. This value is used as
   * the expiry time set in the authentication data.
   */
  @Property(intValue = DEFAULT_AUTH_TIMEOUT)
  static final String PAR_AUTH_TIMEOUT = "sakai.auth.ldap.login.timeout";

  static final String DEFAULT_TOKEN_FILE = "cookie-tokens.bin";
  
  /** The name of the file used to persist the security tokens */
  @Property(value = DEFAULT_TOKEN_FILE)
  static final String PAR_TOKEN_FILE = "sakai.auth.ldap.token.file";

  /**
   * The request method required for user name and password submission by the form (value
   * is "POST").
   */
  static final String REQUEST_METHOD = "POST";

  /**
   * The last segment of the request URL for the user name and password submission by the
   * form (value is "/j_security_check").
   * <p/>
   * This name is derived from the prescription in the Servlet API 2.4 Specification,
   * Section SRV.12.5.3.1 Login Form Notes: <i>In order for the authentication to proceeed
   * appropriately, the action of the login form must always be set to
   * <code>j_security_check</code>.</i>
   */
//  static final String REQUEST_URL_SUFFIX = "/j_security_check";
  /** override to match sakai nakamura formauth */
  static final String REQUEST_URL_SUFFIX = "/system/sling/formlogin";

  /**
   * The name of the form submission parameter providing the name of the user to
   * authenticate (value is "j_username").
   * <p/>
   * This name is prescribed by the Servlet API 2.4 Specification, Section SRV.12.5.3 Form
   * Based Authentication.
   */
//  static final String PAR_USERNAME = "j_username";
  /** override to match sakai nakamura formauth */
  static final String PAR_USERNAME = "sakaiauth:un";

  /**
   * The name of the form submission parameter providing the password of the user to
   * authenticate (value is "j_password").
   * <p/>
   * This name is prescribed by the Servlet API 2.4 Specification, Section SRV.12.5.3 Form
   * Based Authentication.
   */
//  static final String PAR_J_PASSWORD = "j_password";
  /** override to match sakai nakamura formauth */
  static final String PAR_PASSWORD = "sakaiauth:pw";
  /**
   * The name of the form submission parameter indicating that the submitted username and
   * password should just be checked and a status code be set for success (200/OK) or
   * failure (403/FORBIDDEN).
   */
  static final String PAR_VALIDATE = "j_validate";

  /**
   * The name of the request parameter indicating to the login form why the form is being
   * rendered. If this parameter is not set the form is called for the first time and the
   * implied reason is that the authenticator just requests credentials. Otherwise the
   * parameter is set to a {@link FormReason} value.
   */
  static final String PAR_REASON = "j_reason";

  /**
   * The name of the request parameter indicating that we should try to login
   */
  static final String PAR_LOGIN = "sakaiauth:login";
  
  /** The factor to convert minute numbers into milliseconds used internally */
  private static final long MINUTES = 60L * 1000L;

  /** default log */
  private final Logger log = LoggerFactory.getLogger(getClass());

  private AuthenticationStorage authStorage;

  private String loginForm;

  /**
   * The timeout of a login session in milliseconds, converted from the configuration
   * property {@link #PAR_AUTH_TIMEOUT} by multiplying with {@link #MINUTES}.
   */
  private long sessionTimeout;

  /** The name of the credentials attribute which is set to the cookie data to validate. */
  private String attrCookieAuthData;

  /** The {@link TokenStore} used to persist and check authentication data */
  private TokenStore tokenStore;

  /**
   * Extracts cookie/session based credentials from the request. Returns <code>null</code>
   * if the handler assumes HTTP Basic authentication would be more appropriate, if no
   * form fields are present in the request and if the secure user data is not present
   * either in the cookie or an HTTP Session.
   */
  public AuthenticationInfo extractCredentials(HttpServletRequest request,
                                               HttpServletResponse response) {

    // 1. try credentials from POST'ed request parameters
    AuthenticationInfo info = this.extractRequestParameterAuthentication(request);

    // 2. try credentials from the cookie or session
    if (info == null) {
      String authData = authStorage.extractAuthenticationInfo(request);
      if (authData != null) {
        if (tokenStore.isValid(authData)) {
          info = createAuthInfo(authData);
        } else {
          // signal the requestCredentials method a previous login failure
          request.setAttribute(PAR_REASON, FormReason.TIMEOUT);
        }
      }
    }

    return info;
  }

  /**
   * Unless the <code>sling:authRequestLogin</code> to anything other than
   * <code>Form</code> this method either sends back a 403/FORBIDDEN response if the
   * <code>j_verify</code> parameter is set to <code>true</code> or redirects to the login
   * form to ask for credentials.
   * <p/>
   * This method assumes the <code>j_verify</code> request parameter to only be set in the
   * initial username/password submission through the login form. No further checks are
   * applied, though, before sending back the 403/FORBIDDEN response.
   */
  public boolean requestCredentials(HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {

    // 0. ignore this handler if an authentication handler is requested
    if (ignoreRequestCredentials(request)) {
      // consider this handler is not used
      return false;
    }

    // 1. check whether we short cut for a failed log in with validation
    if (isValidateRequest(request)) {
      try {
        response.setStatus(403);
        response.flushBuffer();
      } catch (IOException ioe) {
        log.error("Failed to send 403/FORBIDDEN response", ioe);
      }

      // consider credentials requested
      return true;
    }

    // prepare the login form redirection target
    final StringBuilder targetBuilder = new StringBuilder();
    targetBuilder.append(request.getContextPath());
    targetBuilder.append(loginForm);

    // append originally requested resource (for redirect after login)
    char parSep = '?';
    final String resource = getLoginResource(request);
    if (resource != null) {
      targetBuilder.append(parSep).append(Authenticator.LOGIN_RESOURCE);
      targetBuilder.append("=").append(URLEncoder.encode(resource, "UTF-8"));
      parSep = '&';
    }

    // append indication of previous login failure
    if (request.getAttribute(PAR_REASON) != null) {
      final String reason = String.valueOf(request.getAttribute(PAR_REASON));
      targetBuilder.append(parSep).append(PAR_REASON);
      targetBuilder.append("=").append(URLEncoder.encode(reason, "UTF-8"));
    }

    // finally redirect to the login form
    final String target = targetBuilder.toString();
    try {
      response.sendRedirect(target);
    } catch (IOException e) {
      log.error("Failed to redirect to the page: " + target, e);
    }

    return true;
  }

  /**
   * Clears all authentication state which might have been prepared by this authentication
   * handler.
   */
  public void dropCredentials(HttpServletRequest request, HttpServletResponse response) {
    authStorage.clear(request, response);
  }

  // ---------- AuthenticationFeedbackHandler

  /**
   * Called after an unsuccessful login attempt. This implementation makes sure the
   * authentication data is removed either by removing the cookie or by remove the HTTP
   * Session attribute.
   */
  public void authenticationFailed(HttpServletRequest request,
                                   HttpServletResponse response,
                                   AuthenticationInfo authInfo) {

    /*
         * Note: This method is called if this handler provided credentials which cause a
         * login failure
         */

    // clear authentication data from Cookie or Http Session
    authStorage.clear(request, response);

    // signal the requestCredentials method a previous login failure
    request.setAttribute(PAR_REASON, FormReason.INVALID_CREDENTIALS);
  }

  /**
   * Called after successfull login with the given authentication info. This
   * implementation ensures the authentication data is set in either the cookie or the
   * HTTP session with the correct security tokens.
   * <p/>
   * If no authentication data already exists, it is created. Otherwise if the data has
   * expired the data is updated with a new security token and a new expiry time.
   * <p/>
   * If creating or updating the authentication data fails, it is actually removed from
   * the cookie or the HTTP session and future requests will not be authenticated any
   * longer.
   */
  public boolean authenticationSucceeded(HttpServletRequest request,
                                         HttpServletResponse response,
                                         AuthenticationInfo authInfo) {

    /*
         * Note: This method is called if this handler provided credentials which succeeded
         * loging into the repository
         */

    // ensure fresh authentication data
    refreshAuthData(request, response, authInfo);

    final boolean result;
    if (isValidateRequest(request)) {

      try {
        response.setStatus(200);
        response.flushBuffer();
      } catch (IOException ioe) {
        log.error("Failed to send 200/OK response", ioe);
      }

      // terminate request, all done
      result = true;

    } else if (DefaultAuthenticationFeedbackHandler.handleRedirect(request, response)) {

      // terminate request, all done in the default handler
      result = false;

    } else {

      // check whether redirect is requested by the resource parameter

      final String resource = getLoginResource(request);
      if (resource != null) {
        try {
          response.sendRedirect(resource);
        } catch (IOException ioe) {
          log.error("Failed to send redirect to: " + resource, ioe);
        }

        // terminate request, all done
        result = true;
      } else {
        // no redirect, hence continue processing
        result = false;
      }

    }

    // no redirect
    return result;
  }

  @Override
  public String toString() {
    return "Form Based LDAP Authentication Handler";
  }

  // --------- Force HTTP Basic Auth ---------

  /**
   * Returns <code>true</code> if this authentication handler should ignore the call to
   * {@link #requestCredentials(HttpServletRequest, HttpServletResponse)}.
   * <p/>
   * This method returns <code>true</code> if the {@link #REQUEST_LOGIN_PARAMETER} is set
   * to any value other than "Form" (HttpServletRequest.FORM_AUTH).
   *
   * @param request Request to check for credentials
   *
   * @return true if login parameter is "form", otherwise false.
   */
  private boolean ignoreRequestCredentials(final HttpServletRequest request) {
    final String requestLogin = request.getParameter(REQUEST_LOGIN_PARAMETER);
    return requestLogin != null && !HttpServletRequest.FORM_AUTH.equals(requestLogin);
  }

  /**
   * Returns <code>true</code> if the the client just asks for validation of submitted
   * username/password credentials.
   * <p/>
   * This implementation returns <code>true</code> if the request parameter {@link
   * #PAR_VALIDATE} is set to <code>true</code> (case-insensitve). If the request
   * parameter is not set or to any value other than <code>true</code> this method returns
   * <code>false</code>.
   *
   * @param request The request to provide the parameter to check
   *
   * @return <code>true</code> if the {@link #PAR_VALIDATE} parameter is set to
   *         <code>true</code>.
   */
  private boolean isValidateRequest(final HttpServletRequest request) {
    return "true".equalsIgnoreCase(request.getParameter(PAR_VALIDATE));
  }

  /**
   * Ensures the authentication data is set (if not set yet) and the expiry time is
   * prolonged (if auth data already existed).
   * <p/>
   * This method is intended to be called in case authentication succeeded.
   *
   * @param request  The curent request
   * @param response The current response
   * @param authInfo The authentication info used to successfull log in
   */
  private void refreshAuthData(final HttpServletRequest request,
                               final HttpServletResponse response,
                               final AuthenticationInfo authInfo) {

    // get current authentication data, may be missing after first login
    String authData = getCookieAuthData((Credentials) authInfo
        .get(AuthenticationInfo.CREDENTIALS));

    // check whether we have to "store" or create the data
    final boolean refreshCookie = needsRefresh(authData, this.sessionTimeout);

    // add or refresh the stored auth hash
    if (refreshCookie) {
      long expires = System.currentTimeMillis() + this.sessionTimeout;
      try {
        authData = null;
        authData = tokenStore.encode(expires, authInfo.getUser());
      } catch (InvalidKeyException e) {
        log.error(e.getMessage(), e);
      } catch (IllegalStateException e) {
        log.error(e.getMessage(), e);
      } catch (UnsupportedEncodingException e) {
        log.error(e.getMessage(), e);
      } catch (NoSuchAlgorithmException e) {
        log.error(e.getMessage(), e);
      }

      if (authData != null) {
        authStorage.set(request, response, authData);
      } else {
        authStorage.clear(request, response);
      }
    }
  }

  /**
   * Returns any resource target to redirect to after successful authentication. This
   * method either returns a non-empty string or <code>null</code>. First the
   * <code>resource</code> request attribute is checked. If it is a non-empty string, it
   * is returned. Second the <code>resource</code> request parameter is checked and
   * returned if it is a non-empty string.
   *
   * @param request The request providing the attribute or parameter
   *
   * @return The non-empty redirection target or <code>null</code>.
   */
  static String getLoginResource(final HttpServletRequest request) {

    // return the resource attribute if set to a non-empty string
    Object resObj = request.getAttribute(Authenticator.LOGIN_RESOURCE);
    if ((resObj instanceof String) && ((String) resObj).length() > 0) {
      return (String) resObj;
    }

    // return the resource parameter if not set or set to a non-empty value
    final String resource = request.getParameter(Authenticator.LOGIN_RESOURCE);
    if (resource == null || resource.length() > 0) {
      return resource;
    }

    // normalize empty resource string to null
    return null;
  }

  // --------- Request Parameter Auth ---------

  private AuthenticationInfo extractRequestParameterAuthentication(
      HttpServletRequest request) {
    AuthenticationInfo info = null;

    // only consider login form parameters if this is a POST request
    // to the j_security_check URL
    if (REQUEST_METHOD.equals(request.getMethod())
        && request.getRequestURI().endsWith(REQUEST_URL_SUFFIX)
        && "1".equals(request.getParameter(PAR_LOGIN))) {

      String user = request.getParameter(PAR_USERNAME);
      String pwd = request.getParameter(PAR_PASSWORD);

      if (user != null && pwd != null) {
        info = new AuthenticationInfo(LDAP_AUTH, user, pwd.toCharArray());

        // if this request is providing form credentials, we have to
        // make sure, that the request is redirected after successful
        // authentication, otherwise the request may be processed
        // as a POST request to the j_security_check page (unless
        // the j_validate parameter is set)
        if (getLoginResource(request) == null) {
          request.setAttribute(Authenticator.LOGIN_RESOURCE, "/");
        }
      }
    }

    return info;
  }

  private AuthenticationInfo createAuthInfo(final String authData) {
    final String userId = getUserId(authData);
    if (userId == null) {
      return null;
    }

    final SimpleCredentials cookieAuthCredentials = new SimpleCredentials(userId,
        new char[0]);
    cookieAuthCredentials.setAttribute(attrCookieAuthData, authData);

    final AuthenticationInfo info = new AuthenticationInfo(LDAP_AUTH, userId);
    info.put(AuthenticationInfo.CREDENTIALS, cookieAuthCredentials);

    return info;
  }

  // ---------- LoginModulePlugin support

  private String getCookieAuthData(final Credentials credentials) {
    if (credentials instanceof SimpleCredentials) {
      Object data = ((SimpleCredentials) credentials).getAttribute(attrCookieAuthData);
      if (data instanceof String) {
        return (String) data;
      }
    }

    // no SimpleCredentials or no valid attribute
    return null;
  }

  boolean hasAuthData(final Credentials credentials) {
    return getCookieAuthData(credentials) != null;
  }

  boolean isValid(final Credentials credentials) {
    String authData = getCookieAuthData(credentials);
    if (authData != null) {
      return tokenStore.isValid(authData);
    }

    // no authdata, not valid
    return false;
  }

  // ---------- SCR Integration ----------------------------------------------

  /**
   * Called by SCR to activate the authentication handler.
   *
   * @throws InvalidKeyException
   * @throws NoSuchAlgorithmException
   * @throws IllegalStateException
   * @throws UnsupportedEncodingException
   */
  @Activate
  protected void activate(ComponentContext componentContext) throws InvalidKeyException,
      NoSuchAlgorithmException, IllegalStateException, UnsupportedEncodingException {

    Dictionary properties = componentContext.getProperties();

    this.loginForm = OsgiUtil.toString(properties.get(PAR_LOGIN_FORM),
        LdapAuthenticationServlet.SERVLET_PATH);
    log.info("Login Form URL {}", loginForm);

    final String authName = OsgiUtil.toString(properties.get(PAR_AUTH_NAME),
        DEFAULT_AUTH_NAME);
    final String authStorage = OsgiUtil.toString(properties.get(PAR_AUTH_STORAGE),
        DEFAULT_AUTH_STORAGE);
    if (AUTH_STORAGE_SESSION_ATTRIBUTE.equals(authStorage)) {

      this.authStorage = new SessionStorage(authName);
      log.info("Using HTTP Session store with attribute name {}", authName);

    } else {

      this.authStorage = new CookieStorage(authName);
      log.info("Using Cookie store with name {}", authName);

    }

    this.attrCookieAuthData = OsgiUtil.toString(properties
        .get(PAR_CREDENTIALS_ATTRIBUTE_NAME), DEFAULT_CREDENTIALS_ATTRIBUTE_NAME);
    log.info("Setting Auth Data attribute name {}", attrCookieAuthData);

    int timeoutMinutes = OsgiUtil.toInteger(properties.get(PAR_AUTH_TIMEOUT),
        DEFAULT_AUTH_TIMEOUT);
    if (timeoutMinutes < 1) {
      timeoutMinutes = DEFAULT_AUTH_TIMEOUT;
    }
    log.info("Setting session timeout {} minutes", timeoutMinutes);
    this.sessionTimeout = MINUTES * timeoutMinutes;

    final String tokenFileName = OsgiUtil.toString(properties.get(PAR_TOKEN_FILE),
        DEFAULT_TOKEN_FILE);
    final File tokenFile = getTokenFile(tokenFileName, componentContext
        .getBundleContext());
    log.info("Storing tokens in ", tokenFile);
    this.tokenStore = new TokenStore(tokenFile, sessionTimeout);
  }

  /**
   * Returns an absolute file indicating the file to use to persist the security tokens.
   * <p/>
   * This method is not part of the API of this class and is package private to enable
   * unit tests.
   *
   * @param tokenFileName The configured file name, must not be null
   * @param bundleContext The BundleContext to use to make an relative file absolute
   *
   * @return The absolute file
   */
  File getTokenFile(final String tokenFileName, final BundleContext bundleContext) {
    File tokenFile = new File(tokenFileName);
    if (tokenFile.isAbsolute()) {
      return tokenFile;
    }

    tokenFile = bundleContext.getDataFile(tokenFileName);
    if (tokenFile == null) {
      final String slingHome = bundleContext.getProperty("sling.home");
      if (slingHome != null) {
        tokenFile = new File(slingHome, tokenFileName);
      } else {
        tokenFile = new File(tokenFileName);
      }
    }

    return tokenFile.getAbsoluteFile();
  }

  /**
   * Returns the user id from the authentication data. If the authentication data is a
   * non-<code>null</code> value with 3 fields separated by an @ sign, the value of the
   * third field is returned. Otherwise <code>null</code> is returned.
   * <p/>
   * This method is not part of the API of this class and is package private to enable
   * unit tests.
   *
   * @param authData Authorization data to get user ID from.
   *
   * @return User ID if found, otherwise null.
   */
  String getUserId(final String authData) {
    if (authData != null) {
      String[] parts = StringUtils.split(authData, "@");
      if (parts != null && parts.length == 3) {
        return parts[2];
      }
    }
    return null;
  }

  /**
   * Refresh the cookie periodically.
   *
   * @param authData       Authorization data to check.
   * @param sessionTimeout time to live for the session
   *
   * @return true or false
   */
  private boolean needsRefresh(final String authData, final long sessionTimeout) {
    boolean updateCookie = false;
    if (authData == null) {
      updateCookie = true;
    } else {
      String[] parts = StringUtils.split(authData, "@");
      if (parts != null && parts.length == 3) {
        long cookieTime = Long.parseLong(parts[1].substring(1));
        if (System.currentTimeMillis() + (sessionTimeout / 2) > cookieTime) {
          updateCookie = true;
        }
      }
    }
    return updateCookie;
  }

  /**
   * The <code>AuthenticationStorage</code> interface abstracts the API required to store
   * auth data in an HTTP cookie or in an HTTP Session. The concrete
   * class -- {@link CookieStorage} or {@link SessionStorage} -- is selected using the
   * {@link LdapAuthenticationHandler#PAR_AUTH_STORAGE} configuration parameter,
   * {@link CookieStorage} by default.
   */
  private static interface AuthenticationStorage {
    String extractAuthenticationInfo(HttpServletRequest request);

    void set(HttpServletRequest request, HttpServletResponse response, String authData);

    void clear(HttpServletRequest request, HttpServletResponse response);
  }

  /**
   * The <code>CookieExtractor</code> class supports storing the auth data in
   * an HTTP Cookie.
   */
  private static class CookieStorage implements AuthenticationStorage {
    private final String cookieName;

    public CookieStorage(final String cookieName) {
      this.cookieName = cookieName;
    }

    public String extractAuthenticationInfo(HttpServletRequest request) {
      Cookie[] cookies = request.getCookies();
      if (cookies != null) {
        for (Cookie cookie : cookies) {
          if (this.cookieName.equals(cookie.getName())) {
            // found the cookie, so try to extract the credentials
            // from it
            String value = cookie.getValue();

            // reverse the base64 encoding
            try {
              return new String(Base64.decodeBase64(value), "UTF-8");
            } catch (UnsupportedEncodingException e1) {
              throw new RuntimeException(e1);
            }
          }
        }
      }

      return null;
    }

    public void set(HttpServletRequest request, HttpServletResponse response,
                    String authData) {
      // base64 encode to handle any special characters
      String cookieValue;
      try {
        cookieValue = Base64.encodeBase64URLSafeString(authData.getBytes("UTF-8"));
      } catch (UnsupportedEncodingException e1) {
        throw new RuntimeException(e1);
      }

      // send the cookie to the response
      setCookie(request, response, cookieValue, -1);
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
      Cookie oldCookie = null;
      Cookie[] cookies = request.getCookies();
      if (cookies != null) {
        for (Cookie cookie : cookies) {
          if (this.cookieName.equals(cookie.getName())) {
            // found the cookie
            oldCookie = cookie;
            break;
          }
        }
      }

      // remove the old cookie from the client
      if (oldCookie != null) {
        setCookie(request, response, "", 0);
      }
    }

    private void setCookie(final HttpServletRequest request,
                           final HttpServletResponse response, final String value,
                           final int age) {

      final String ctxPath = request.getContextPath();
      final String cookiePath = (ctxPath == null || ctxPath.length() == 0) ? "/"
          : ctxPath;

      Cookie cookie = new Cookie(this.cookieName, value);
      cookie.setMaxAge(age);
      cookie.setPath(cookiePath);
      cookie.setSecure(request.isSecure());
      response.addCookie(cookie);
    }
  }

  /**
   * The <code>SessionStorage</code> class provides support to store the auth
   * data in an HTTP Session.
   */
  private static class SessionStorage implements AuthenticationStorage {
    private final String sessionAttributeName;

    SessionStorage(final String sessionAttributeName) {
      this.sessionAttributeName = sessionAttributeName;
    }

    public String extractAuthenticationInfo(HttpServletRequest request) {
      HttpSession session = request.getSession(false);
      if (session != null) {
        Object attribute = session.getAttribute(sessionAttributeName);
        if (attribute instanceof String) {
          return (String) attribute;
        }
      }
      return null;
    }

    public void set(HttpServletRequest request, HttpServletResponse response,
                    String authData) {
      // store the auth hash as a session attribute
      HttpSession session = request.getSession();
      session.setAttribute(sessionAttributeName, authData);
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
      HttpSession session = request.getSession(false);
      if (session != null) {
        session.removeAttribute(sessionAttributeName);
      }
    }

  }
}
