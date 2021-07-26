# ShinyProxy

This page describes how the operator and ShinyProxy interact with each other.
The operator and ShinyProxy do not communicate with each other, nevertheless
both systems are designed to be aware of each other in order to provide the best
user experience.

**Note:** this page is only applicable to ShinyProxy 2.6.0.

As described in the README, the main goal of the operator is to allow an
administrator to change the configuration of ShinyProxy without affecting the
connection and session of currently running apps. Every time the configuration
of ShinyProxy is updated, the operator creates a new ShinyProxy server. The
previous running server will not be removed by the operator immediately.
Instead, it is kept around as long as users are running apps on that server.
Theses users will stay on the old server, until they are transferred to the new
server. In contrast, new users will automatically get routed to the latest
server and run their apps on that server.

## Transferring users to the latest ShinyProxy Server

This sections describe the scenarios in which a user is transferred to the
latest server.

### Scenario 1: user action

**Assumptions:**

- the user has at least one app running on the old server

**Behavior:**

- since the user has an app running it will not automatically be transferred to
the latest version.
- a message on the main page of ShinyProxy is shown:

  ![Message on the app page](../../.github/screenshots/message_main_page.png)

- a message on the app page of ShinyProxy is shown:

  ![Message on the app page](../../.github/screenshots/message_app_page.png)

- after clicking the button on the message the user is transferred to the latest
  ShinyProxy server

**Note:** both messages can be disabled by setting the
`proxy.operator.show-transfer-message-main-page` and
`proxy.operator.show-transfer-message-app-page` options to `false` (in the
ShinyProxy configuration). By default these messages are enabled.

### Scenario 2: After logout

**Assumptions:**

- the user is logged in on an old server
- it does not matter whether the user has apps running or not

**Behavior:**

- when the user logs out, they are transferred to the latest ShinyProxy server

**Note**: this feature may not work with every authentication backend (e.g. when
using SAML), however, the user will be transferred to the latest ShinyProxy
server when they try to re-login.

### Scenario 3: Before login

**Assumptions:**

- the user was using an older server at some point
- the user was logged out (e.g. because their session expired or because they clicked the logout button (see Scenario 2))

**Behavior:**

- when the user opens ShinyProxy, they will be routed to the old server. This
  old server then transfer the user to the latest ShinyProxy version before the
  user can login.

**Note**: this feature should work with every authentication backend and
configuration. For example, in the case of OpenID, the old server will not
redirect the user to the OpenID provider, but directly to the latest ShinyProxy
server. This server will then redirect to the OpenID provider.

### Scenario 4: Opening the main page

**Assumptions:**

- the user was using an old ShinyProxy server and is still logged in, but
  currently has no apps running (e.g. because they clicked the stop button) and
  opens the ShinyProxy webpage
- or, the user is not logged in, but still has the login form open on an old
  server and logs in on an old server

**Behavior:**

- when the user lands on the main page of ShinyProxy, they are transferred to
  the latest ShinyProxy server

**Note:** this only works when the `proxy.operator.force-transfer` option is set
to `true` (in the ShinyProxy configuration).

### Scenario 5: Starting an app

**Assumptions:**

- the user was using an old ShinyProxy server and is still logged in, but
  currently has no apps running (e.g. because they clicked the stop or restart button) and
  opens an app page
- or, the user is not logged in, but still has the login form open on an old
  server and logs in on an old server which sends them directly to an app page

**Behavior:**

- before ShinyProxy starts the request app, the user is transferred to the
  latest ShinyProxy server

**Note:** this only works when the `proxy.operator.force-transfer` option is set
to `true` (in the ShinyProxy configuration). By default this option is disabled.