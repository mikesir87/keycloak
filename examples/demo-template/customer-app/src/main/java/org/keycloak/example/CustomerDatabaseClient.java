package org.keycloak.example;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.keycloak.KeycloakAuthenticatedSession;
import org.keycloak.adapters.HttpClientBuilder;
import org.keycloak.util.JsonSerialization;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class CustomerDatabaseClient {

    static class TypedList extends ArrayList<String> {
    }

    public static class Failure extends Exception {
        private int status;

        public Failure(int status) {
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    public static List<String> getCustomers(HttpServletRequest req) throws Failure {
        KeycloakAuthenticatedSession session = (KeycloakAuthenticatedSession) req.getAttribute(KeycloakAuthenticatedSession.class.getName());

        HttpClient client = new HttpClientBuilder()
                .trustStore(session.getMetadata().getTruststore())
                .hostnameVerification(HttpClientBuilder.HostnameVerificationPolicy.ANY).build();
        try {
            HttpGet get = new HttpGet("http://localhost:8080/database/customers");
            get.addHeader("Authorization", "Bearer " + session.getTokenString());
            try {
                HttpResponse response = client.execute(get);
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new Failure(response.getStatusLine().getStatusCode());
                }
                HttpEntity entity = response.getEntity();
                InputStream is = entity.getContent();
                try {
                    return JsonSerialization.readValue(is, TypedList.class);
                } finally {
                    is.close();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } finally {
            client.getConnectionManager().shutdown();
        }
    }
}
