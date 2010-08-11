package org.sakaiproject.nakamura.casauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.principal.ItemBasedPrincipal;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.AuthorizableExistsException;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.commons.auth.spi.AuthenticationInfo;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.jackrabbit.server.security.LoginModulePlugin;
import org.apache.sling.servlets.post.Modification;
import org.jasig.cas.client.authentication.AttributePrincipal;
import org.jasig.cas.client.validation.Assertion;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.sakaiproject.nakamura.api.user.AuthorizablePostProcessService;
import org.sakaiproject.nakamura.api.user.UserConstants;
import org.sakaiproject.nakamura.auth.sso.SsoAuthenticationHandler;
import org.sakaiproject.nakamura.auth.sso.SsoAuthenticationPlugin;

import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.ValueFactory;
import javax.security.auth.login.FailedLoginException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@RunWith(MockitoJUnitRunner.class)
public class CasAuthenticationHandlerTest {
  private SsoAuthenticationHandler casAuthenticationHandler;
  private SsoAuthenticationPlugin casAuthenticationPlugin;
  private SimpleCredentials casCredentials;
  @Mock
  HttpServletRequest request;
  @Mock
  HttpServletResponse response;
  @Mock
  HttpSession session;
  @Mock
  ValueFactory valueFactory;
  @Mock
  Assertion assertion;
  @Mock
  private AttributePrincipal casPrincipal;
  @Mock
  private SlingRepository repository;
  @Mock
  private JackrabbitSession adminSession;
  @Mock
  private UserManager userManager;

  @Before
  public void setUp() throws RepositoryException {
    casAuthenticationHandler = new SsoAuthenticationHandler();
    casAuthenticationHandler.repository = repository;
    when(adminSession.getUserManager()).thenReturn(userManager);
    when(adminSession.getValueFactory()).thenReturn(valueFactory);
    when(repository.loginAdministrative(null)).thenReturn(adminSession);
  }

  // AuthenticationHandler tests.

  @Test
  public void testAuthenticateNoTicket() {
    assertNull(casAuthenticationHandler.extractCredentials(request, response));
  }

  @Test
  public void testDropNoSession() throws IOException {
    casAuthenticationHandler.dropCredentials(request, response);
  }

  @Test
  public void testDropNoAssertion() throws IOException {
    when(session.getAttribute(SsoAuthenticationHandler.CONST_CAS_ASSERTION)).thenReturn(null);
    when(request.getSession(false)).thenReturn(session);
    casAuthenticationHandler.dropCredentials(request, response);
  }

  @Test
  public void testDropWithAssertion() throws IOException {
    Assertion assertion = mock(Assertion.class);
    when(session.getAttribute(SsoAuthenticationHandler.CONST_CAS_ASSERTION)).thenReturn(assertion);
    when(request.getSession(false)).thenReturn(session);
    casAuthenticationHandler.dropCredentials(request, response);
    verify(session).removeAttribute(SsoAuthenticationHandler.CONST_CAS_ASSERTION);
  }

  private void setUpCasCredentials() {
    when(casPrincipal.getName()).thenReturn("joe");
    when(assertion.getPrincipal()).thenReturn(casPrincipal);
    when(session.getAttribute(SsoAuthenticationHandler.CONST_CAS_ASSERTION)).thenReturn(
        assertion);
    when(request.getSession(false)).thenReturn(session);
    AuthenticationInfo authenticationInfo = casAuthenticationHandler.extractCredentials(request, response);
    casCredentials = (SimpleCredentials) authenticationInfo.get(AuthenticationInfo.CREDENTIALS);
  }

  @Test
  public void testExtractCredentialsFromAssertion() {
    setUpCasCredentials();
    assertEquals(casCredentials.getUserID(), "joe");
  }

  // LoginModulePlugin tests.

  @Test
  public void testCanHandleCasCredentials() throws RepositoryException {
    setUpCasCredentials();
    assertTrue(casAuthenticationHandler.canHandle(casCredentials));
  }

  @Test
  public void testCannotHandleOtherCredentials() {
    SimpleCredentials credentials = new SimpleCredentials("joe", new char[0]);
    assertFalse(casAuthenticationHandler.canHandle(credentials));
  }

  @Test
  public void testGetPrincipal() {
    setUpCasCredentials();
    assertEquals("joe", casAuthenticationHandler.getPrincipal(casCredentials).getName());
  }

  @Test
  public void testImpersonate() throws FailedLoginException, RepositoryException {
    assertEquals(LoginModulePlugin.IMPERSONATION_DEFAULT, casAuthenticationHandler.impersonate(null, null));
  }

  // AuthenticationPlugin tests.

  @Test
  public void testDoNotAuthenticateUser() throws RepositoryException {
    casAuthenticationPlugin = new SsoAuthenticationPlugin(casAuthenticationHandler);
    assertFalse(casAuthenticationPlugin.authenticate(casCredentials));
  }

  @Test
  public void testAuthenticateUser() throws RepositoryException {
    setUpCasCredentials();
    casAuthenticationPlugin = new SsoAuthenticationPlugin(casAuthenticationHandler);
    assertTrue(casAuthenticationPlugin.authenticate(casCredentials));
  }

  // AuthenticationFeedbackHandler tests.

  private void setAutocreateUser(String bool) {
    Map<String, String> properties = new HashMap<String, String>();
    properties.put(SsoAuthenticationHandler.SSO_AUTOCREATE_USER, bool);
    casAuthenticationHandler.activate(properties);
  }

  @Test
  public void testUnknownUserNoCreation() throws AuthorizableExistsException, RepositoryException {
    setAutocreateUser("false");
    setUpCasCredentials();
    AuthenticationInfo authenticationInfo = casAuthenticationHandler.extractCredentials(request, response);
    boolean actionTaken = casAuthenticationHandler.authenticationSucceeded(request, response, authenticationInfo);
    assertFalse(actionTaken);
    verify(userManager, never()).createUser(anyString(), anyString());
    verify(userManager, never()).createUser(anyString(), anyString(), any(Principal.class), anyString());
  }

  @Test
  public void testUnknownUserWithFailedCreation() throws AuthorizableExistsException, RepositoryException {
    setAutocreateUser("true");
    doThrow(new AuthorizableExistsException("Hey Joe")).when(userManager).createUser(anyString(), anyString());
    setUpCasCredentials();
    AuthenticationInfo authenticationInfo = casAuthenticationHandler.extractCredentials(request, response);
    boolean actionTaken = casAuthenticationHandler.authenticationSucceeded(request, response, authenticationInfo);
    assertTrue(actionTaken);
    verify(userManager).createUser(eq("joe"), anyString());
  }

  @Test
  public void testKnownUserWithCreation() throws AuthorizableExistsException, RepositoryException {
    setAutocreateUser("true");
    User jcrUser = mock(User.class);
    when(jcrUser.getID()).thenReturn("joe");
    when(userManager.getAuthorizable("joe")).thenReturn(jcrUser);
    setUpCasCredentials();
    AuthenticationInfo authenticationInfo = casAuthenticationHandler.extractCredentials(request, response);
    boolean actionTaken = casAuthenticationHandler.authenticationSucceeded(request, response, authenticationInfo);
    assertFalse(actionTaken);
    verify(userManager, never()).createUser(eq("joe"), anyString());
  }

  private void setUpPseudoCreateUserService() throws Exception {
    User jcrUser = mock(User.class);
    when(jcrUser.getID()).thenReturn("joe");
    ItemBasedPrincipal principal = mock(ItemBasedPrincipal.class);
    when(principal.getPath()).thenReturn(UserConstants.USER_REPO_LOCATION + "/joes");
    when(jcrUser.getPrincipal()).thenReturn(principal);
    when(userManager.createUser(eq("joe"), anyString())).thenReturn(jcrUser);
  }

  @Test
  public void testUnknownUserWithCreation() throws Exception {
    setAutocreateUser("true");
    setUpCasCredentials();
    setUpPseudoCreateUserService();
    AuthenticationInfo authenticationInfo = casAuthenticationHandler.extractCredentials(request, response);
    boolean actionTaken = casAuthenticationHandler.authenticationSucceeded(request, response, authenticationInfo);
    assertFalse(actionTaken);
    verify(userManager).createUser(eq("joe"), anyString());
  }

  @Test
  public void testPostProcessingAfterUserCreation() throws Exception {
    AuthorizablePostProcessService postProcessService = mock(AuthorizablePostProcessService.class);
    casAuthenticationHandler.authorizablePostProcessService = postProcessService;
    setAutocreateUser("true");
    setUpCasCredentials();
    setUpPseudoCreateUserService();
    AuthenticationInfo authenticationInfo = casAuthenticationHandler.extractCredentials(request, response);
    boolean actionTaken = casAuthenticationHandler.authenticationSucceeded(request, response, authenticationInfo);
    assertFalse(actionTaken);
    verify(postProcessService).process(any(Authorizable.class), any(Session.class), any(Modification.class));
  }
}
