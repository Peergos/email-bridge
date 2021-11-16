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

    [{ "username": "test", "emailAddress": "", "smtpUsername": "", "smtpPassword": "", "imapUsername": "", "imapPassword": ""}]

config.txt

    sendIntervalSeconds: 30
    receiveInitialDelaySeconds: 30
    receiveIntervalSeconds: 30
    maxNumberOfUnreadEmails: 100

**Execution**

    java -jar EmailBridge.jar -username blah -password **** -peergos-url http://localhost:8000 -is-public-server false -smtp-host smtpHost -smtp-port 465 -imap-host imapHost -imap-port 993

Where username/password is the designated peergos user created for this purpose

**Usage**
Example with 2 users, 'bridge' and alice
1. Make sure instance of Peergos is running
2. Create the bridge user for use by the email bridge
3. Log into peergos as alice with ?email=true added to URL and click on the email icon on the UI toolbar
4. Enter email bridge username when prompted (this sends a friend request to bridge)
5. Log in as bridge and 'allow and follow back' the friend request
6. From alice's perspective, confirm alice now has bridge listed as a friend   
6. Update the properties used in the execution and configuration sections above
7. Start email bridge   

