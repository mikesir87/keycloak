package org.keycloak.services.managers;

import org.jboss.resteasy.logging.Logger;
import org.keycloak.jose.jws.JWSBuilder;
import org.keycloak.models.ApplicationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.AccessScope;
import org.keycloak.representations.AccessToken;
import org.keycloak.util.Base64Url;
import org.keycloak.util.JsonSerialization;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateful object that creates tokens and manages oauth access codes
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class TokenManager {
    protected static final Logger logger = Logger.getLogger(TokenManager.class);

    protected Map<String, AccessCodeEntry> accessCodeMap = new ConcurrentHashMap<String, AccessCodeEntry>();

    public void clearAccessCodes() {
        accessCodeMap.clear();
    }

    public AccessCodeEntry getAccessCode(String key) {
        return accessCodeMap.get(key);
    }

    public AccessCodeEntry pullAccessCode(String key) {
        return accessCodeMap.remove(key);
    }

    protected boolean desiresScope(AccessScope scope, String key, String roleName) {
        if (scope == null || scope.isEmpty()) return true;
        List<String> val = scope.get(key);
        if (val == null) return false;
        return val.contains(roleName);

    }

    protected boolean desiresScopeGroup(AccessScope scope, String key) {
        if (scope == null || scope.isEmpty()) return true;
        return scope.containsKey(key);
    }

    protected boolean isEmpty(AccessScope scope) {
        return scope == null || scope.isEmpty();
    }

    public static void applyScope(RoleModel role, RoleModel scope, Set<RoleModel> visited, Set<RoleModel> requested) {
        if (visited.contains(scope)) return;
        visited.add(scope);
        if (role.hasRole(scope)) {
            requested.add(scope);
            return;
        }
        if (!scope.isComposite()) return;

        for (RoleModel contained : scope.getComposites()) {
            applyScope(role, contained, visited, requested);
        }
    }



    public AccessCodeEntry createAccessCode(String scopeParam, String state, String redirect, RealmModel realm, UserModel client, UserModel user) {
        AccessCodeEntry code = createAccessCodeEntry(scopeParam, state, redirect, realm, client, user);
        accessCodeMap.put(code.getId(), code);
        return code;
    }

    private AccessCodeEntry createAccessCodeEntry(String scopeParam, String state, String redirect, RealmModel realm, UserModel client, UserModel user) {
        AccessCodeEntry code = new AccessCodeEntry();
        List<RoleModel> realmRolesRequested = code.getRealmRolesRequested();
        MultivaluedMap<String, RoleModel> resourceRolesRequested = code.getResourceRolesRequested();

        AccessToken token = createClientAccessToken(scopeParam, realm, client, user, realmRolesRequested, resourceRolesRequested);

        code.setToken(token);
        code.setRealm(realm);
        code.setExpiration((System.currentTimeMillis() / 1000) + realm.getAccessCodeLifespan());
        code.setClient(client);
        code.setUser(user);
        code.setState(state);
        code.setRedirectUri(redirect);
        String accessCode = null;
        try {
            accessCode = new JWSBuilder().content(code.getId().getBytes("UTF-8")).rsa256(realm.getPrivateKey());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        code.setCode(accessCode);
        return code;
    }

    public AccessToken createClientAccessToken(String scopeParam, RealmModel realm, UserModel client, UserModel user) {
        return createClientAccessToken(scopeParam, realm, client, user, new LinkedList<RoleModel>(), new MultivaluedHashMap<String, RoleModel>());
    }


    public AccessToken createClientAccessToken(String scopeParam, RealmModel realm, UserModel client, UserModel user, List<RoleModel> realmRolesRequested, MultivaluedMap<String, RoleModel> resourceRolesRequested) {
        AccessScope scopeMap = null;
        if (scopeParam != null) scopeMap = decodeScope(scopeParam);


        Set<RoleModel> roleMappings = realm.getRoleMappings(user);
        Set<RoleModel> scopeMappings = realm.getScopeMappings(client);
        ApplicationModel clientApp = realm.getApplicationByName(client.getLoginName());
        Set<RoleModel> clientAppRoles = clientApp == null ? null : clientApp.getRoles();
        if (clientAppRoles != null) scopeMappings.addAll(clientAppRoles);

        Set<RoleModel> requestedRoles = new HashSet<RoleModel>();

        for (RoleModel role : roleMappings) {
            if (clientApp != null && role.getContainer().equals(clientApp)) requestedRoles.add(role);
            for (RoleModel desiredRole : scopeMappings) {
                Set<RoleModel> visited = new HashSet<RoleModel>();
                applyScope(role, desiredRole, visited, requestedRoles);
            }
        }

        for (RoleModel role : requestedRoles) {
            if (role.getContainer() instanceof RealmModel && desiresScope(scopeMap, "realm", role.getName())) {
                realmRolesRequested.add(role);
            } else if (role.getContainer() instanceof ApplicationModel) {
                ApplicationModel app = (ApplicationModel)role.getContainer();
                if (desiresScope(scopeMap, app.getName(), role.getName())) {
                    resourceRolesRequested.add(app.getName(), role);

                }
            }
        }

        AccessToken token = initToken(realm, client, user);

        if (realmRolesRequested.size() > 0) {
            for (RoleModel role : realmRolesRequested) {
                addComposites(token, role);
            }
        }

        if (resourceRolesRequested.size() > 0) {
            for (List<RoleModel> roles : resourceRolesRequested.values()) {
                for (RoleModel role : roles) {
                    addComposites(token, role);
                }
            }
        }
        return token;
    }

    protected AccessToken initToken(RealmModel realm, UserModel client, UserModel user) {
        AccessToken token = new AccessToken();
        token.id(KeycloakModelUtils.generateId());
        token.subject(user.getId());
        token.audience(realm.getName());
        token.issuedNow();
        token.issuedFor(client.getLoginName());
        if (realm.getAccessTokenLifespan() > 0) {
            token.expiration((System.currentTimeMillis() / 1000) + realm.getAccessTokenLifespan());
        }
        Set<String> allowedOrigins = client.getWebOrigins();
        if (allowedOrigins != null) {
            token.setAllowedOrigins(allowedOrigins);
        }
        return token;
    }

    protected void addComposites(AccessToken token, RoleModel role) {
        AccessToken.Access access = null;
        if (role.getContainer() instanceof RealmModel) {
            access = token.getRealmAccess();
            if (token.getRealmAccess() == null) {
                access = new AccessToken.Access();
                token.setRealmAccess(access);
            } else if (token.getRealmAccess().getRoles() != null && token.getRealmAccess().isUserInRole(role.getName()))
                return;

        } else {
            ApplicationModel app = (ApplicationModel) role.getContainer();
            access = token.getResourceAccess(app.getName());
            if (access == null) {
                access = token.addAccess(app.getName());
                if (app.isSurrogateAuthRequired()) access.verifyCaller(true);
            } else if (access.isUserInRole(role.getName())) return;

        }
        access.addRole(role.getName());
        if (!role.isComposite()) return;

        for (RoleModel composite : role.getComposites()) {
            addComposites(token, composite);
        }

    }

    public String encodeScope(AccessScope scope) {
        String token = null;
        try {
            token = JsonSerialization.writeValueAsString(scope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return Base64Url.encode(token.getBytes());
    }

    public AccessScope decodeScope(String scopeParam) {
        AccessScope scope = null;
        byte[] bytes = Base64Url.decode(scopeParam);
        try {
            scope = JsonSerialization.readValue(bytes, AccessScope.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return scope;
    }


    public AccessToken createAccessToken(RealmModel realm, UserModel user) {
        AccessToken token = new AccessToken();
        token.id(KeycloakModelUtils.generateId());
        token.issuedNow();
        token.subject(user.getId());
        token.audience(realm.getName());
        if (realm.getAccessTokenLifespan() > 0) {
            token.expiration((System.currentTimeMillis() / 1000) + realm.getAccessTokenLifespan());
        }
        for (RoleModel role : realm.getRoleMappings(user)) {
            addComposites(token, role);
        }
        return token;
    }


    public String encodeToken(RealmModel realm, Object token) {
        String encodedToken = new JWSBuilder()
                .jsonContent(token)
                .rsa256(realm.getPrivateKey());
        return encodedToken;
    }
}
