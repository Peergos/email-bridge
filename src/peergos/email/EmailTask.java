package peergos.email;

import peergos.server.apps.email.EmailBridgeClient;
import peergos.shared.user.UserContext;

import java.util.NoSuchElementException;

public class EmailTask {

    protected EmailBridgeClient buildEmailBridgeClient(UserContext context, String peergosUsername, String emailAddress) {
        EmailBridgeClient bridge = null;
        try {
            return EmailBridgeClient.build(context, peergosUsername, emailAddress);
        } catch(NoSuchElementException ex) {
            return null;
        }
    }
}
