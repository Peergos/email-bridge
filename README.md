# email-bridge
**Email bridge**

The bridge connects to a standard IMAP account and retrieves emails, encrypts them, and stores them in your peergos space. 

It also looks for new outgoing email files in Peergos and sends them as emails via SMTP. 


**Implementation Notes**

- Polling is used to periodically retrieve/send emails.

- The dependencies in /lib are Peergos.jar and Simple Java Mail - https://www.simplejavamail.org/


**Compile**

    ant build.xml

**Execution**

    java -jar EmailBridge.jar -username blah -password **** -peergos-url http://localhost:8000 -is-public-server false -smtp-host smtpHost -smtp-port 465 -imap-host imapHost -imap-port 993

Where username/password is the designated peergos user created for this purpose


**Configuration**

stored in ~/.email-bridge directory

accounts.json

    [{ "username": "test", "emailAddress": "", "smtpUsername": "", "smtpPassword": "", "imapUsername": "", "imapPassword": ""}]

config.txt

    sendIntervalSeconds: 30
    receiveInitialDelaySeconds: 30
    receiveIntervalSeconds: 30
    maxNumberOfUnreadEmails: 100

