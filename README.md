# email-bridge
**Email bridge**

The bridge connects to a standard IMAP account and retrieves emails, encrypts them, and stores them in your peergos space. 

It also looks for new outgoing email files in Peergos and sends them as emails via SMTP. 


**Implementation Notes**

- Polling is used to periodically retrieve/send emails.

- The dependencies in /lib are Peergos.jar and Simple Java Mail - https://www.simplejavamail.org/


**Compile**

    ant build.xml


**Configuration**

stored in ~/.email-bridge directory

accounts.json

    [{ "username": "test", "secretLink": "", "emailAddress": "", "smtpUsername": "", "smtpPassword": "", "imapUsername": "", "imapPassword": ""}]

config.properties

    sendIntervalSeconds: 30
    receiveInitialDelaySeconds: 30
    receiveIntervalSeconds: 30
    maxNumberOfUnreadEmails: 100

**Execution**

    java -jar EmailBridge.jar -peergos-url http://localhost:8000 -is-public-server false -smtp-host smtpHost -smtp-port 465 -imap-host imapHost -imap-port 993

**Usage**
1. Install and run email client app. Make note of the secret link displayed on first run
2. Update the accounts.json property file with details including the secret link from above
3. Start email bridge   
4. Log in again and run email client. You should now be able to send/receive emails
