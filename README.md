OAuth2 Authorization Server — Refresh Token Reuse Detection

Production-grade OAuth2 Authorization Server built from scratch using Spring Authorization Server.
This is not a tutorial implementation — it solves a real security problem that stateless JWT systems face in production:
"How do you revoke a token that was designed to be unrevocable?"


Related Repositories
RepositoryDescriptionoauth2-gatewaySpring Cloud Gateway with session management, rate limiting, and OIDC logoutoauth2-resource-serverProtected API with JTI blacklist enforcement and role-based access control

The Core Problem
JWTs are stateless by design. Once issued, they are valid until expiry — there is no native way to invalidate them. This creates a serious security gap:
ProblemImpactStolen refresh tokenSilently generates new access tokens indefinitelyLogoutDoes not actually revoke tokensStateless architectureNo native mechanism to kill a user's session
This project solves all three.

System Architecture
┌─────────┐
│ Browser │
└────┬────┘
     │
     ▼
┌───────────┐    PORT 8081
│  Gateway  │──  OAuth2 Login · Redis Sessions · Rate Limiting · OIDC Logout
└─────┬─────┘
      │
      ├──────────────────────────────────┐
      │                                  │
      ▼                                  ▼
┌───────────┐    PORT 9000          ┌───────────────┐    PORT 8082
│   Auth    │──  JWT Issuance       │   Resource    │──  JWT Validation
│  Server   │    Refresh Rotation   │    Server     │    JTI Blacklist
│           │    Reuse Detection    │               │    User Revocation
│           │    MySQL + Redis      │               │    Role-based Access
└───────────┘                       └───────────────┘
ComponentPortResponsibilitiesGateway8081OAuth2 login, Redis session management, rate limiting, OIDC logoutAuth Server9000Issues JWTs (5 min TTL), issues refresh tokens (7 day TTL), detects reuse, revokes sessionsResource Server8082Validates JWT signatures, enforces JTI blacklist and user revocation via Redis

Refresh Token Reuse Detection
This is the most critical flow in the system. When a refresh token is stolen and replayed by an attacker:
Step 1 — Legitimate User Rotates Token

Auth server issues a new access token and a new refresh token
The old refresh token is SHA-256 hashed and stored in Redis as used_rt:<hash>
Redis key stores the principal name with an 8-day TTL

Step 2 — Attacker Replays the Old Token

Auth server looks up used_rt:<hash> in Redis
Finds the principal name — reuse detected
Nuclear revocation fires immediately

Step 3 — Nuclear Revocation

Sets revoked_user:<email> in Redis — a user-level kill switch
Queries all active sessions from MySQL for this user
Extracts the JTI from every active access token
Sets blacklisted_jti:<jti> in Redis for each token
Removes all authorizations from the database
Writes a REFRESH_TOKEN_REUSE event to the audit log

Step 4 — Resource Server Enforces Revocation

Every incoming request checks blacklisted_jti:<jti> in Redis
Every incoming request checks revoked_user:<email> in Redis
If either key exists → 401 Unauthorized — token never reaches the controller

Redis State After a Reuse Attack
revoked_user:user1@gmail.com          →  "revoked"        (30 min TTL)
blacklisted_jti:3ab9977f-c21f-...     →  "revoked"        (30 min TTL)
blacklisted_jti:2478c7f8-a5c2-...     →  "revoked"        (30 min TTL)
used_rt:7d5643b845d071b89f84...       →  "user1@gmail.com" (8 day TTL)

Features
Authorization Server
FeatureDescriptionOAuth2 Authorization Code FlowStandard OAuth2 flow for user authenticationRefresh token rotationNew refresh token issued on every useReuse detectionFull session revocation triggered automatically on attackJTI blacklistingIndividual token invalidation via RedisUser-level kill switchInstant blanket revocation via RedisRSA-signed JWTsSigned using a JKS keystore (RS256)BCrypt-hashed secretsSecure client secret storageCustom JWT claimsEmail and roles embedded in every access tokenAsync audit loggingAll auth events persisted to MySQL asynchronously
Gateway
FeatureDescriptionSpring Cloud GatewayOAuth2 login integrationRedis-backed sessions30-minute timeoutRate limiting5 req/sec per user, burst of 10Token relayForwards tokens to downstream servicesOIDC-compliant logoutClears both gateway and SSO session
Resource Server
FeatureDescriptionJWT validationVerified using the auth server's RSA public keyJTI blacklist checkChecked on every request via RedisUser revocation checkChecked on every request via RedisRole-based access controlROLE_USER and ROLE_ADMIN

Redis Key Structure
Key PatternValueTTLPurposeused_rt:<sha256_hash>principal name8 daysMarks a rotated refresh token as usedrevoked_user:<email>"revoked"30 minUser-level kill switch after a reuse attackblacklisted_jti:<jti>"revoked"30 minIndividual access token blacklist entryspring:session:sessions:<id>session data30 minGateway session store (Spring Session)

Audit Log
Every auth event is persisted asynchronously to MySQL:
Event TypeTriggerTOKEN_ISSUEDEvery login and every successful refresh token useTOKEN_ROTATIONWhen a refresh token is rotated (old → new)REFRESH_TOKEN_REUSEWhen a previously used refresh token is submitted again

Token Configuration
ParameterValueAccess Token TTL5 minutesRefresh Token TTL7 daysRefresh Token ReuseDisabled — rotation enforcedSigning AlgorithmRS256 (RSA)Client Authenticationclient_secret_basic

Tech Stack
LayerTechnologyAuthorization ServerSpring Authorization Server 1.xGatewaySpring Cloud Gateway (WebFlux)Resource ServerSpring Security OAuth2 Resource ServerDatabaseMySQL 8Cache / BlacklistRedis 7Session StoreSpring Session RedisToken FormatJWT (RS256)InfrastructureDocker Compose

Running Locally
Prerequisites

Java 21
Maven
Docker

Step 1 — Start Infrastructure
bashdocker-compose up -d
Starts MySQL on port 3307 and Redis on port 6380.
Step 2 — Start Auth Server
bashcd auth-server
cp src/main/resources/application.properties.template src/main/resources/application.properties
mvn spring-boot:run
Step 3 — Start Gateway
bashcd gateway
cp src/main/resources/application.yml.template src/main/resources/application.yml
mvn spring-boot:run
Step 4 — Start Resource Server
bashcd resource-server
cp src/main/resources/application.properties.template src/main/resources/application.properties
mvn spring-boot:run
Step 5 — Access the Application
Navigate to http://localhost:8081/api/me — you will be redirected to the login page.

API Endpoints
EndpointMethodAccessDescription/api/meGETAny authenticated userReturns current user info from JWT/api/admin/dashboardGETROLE_ADMIN onlyAdmin-only protected endpoint/oauth2/tokenPOSTClient credentialsToken endpoint/oauth2/introspectPOSTClient credentialsToken introspection/oauth2/revokePOSTClient credentialsToken revocation/.well-known/openid-configurationGETPublicOIDC discovery endpoint/logoutGETAuthenticatedClears gateway session and SSO

Production Roadmap
EnhancementRationaleKeycloak federationAllow login via external identity providers (Google, corporate SSO)HashiCorp VaultReplace hardcoded secrets with dynamic secret injectionmTLSMutual TLS for service-to-service authenticationPKCE enforcementAlready implemented for public clients; enforce for all clientsScope-based access controlFine-grained permissions beyond roles (read, write, admin)OWASP ZAP scanningAutomated vulnerability scanning integrated into CI/CD pipeline

The Interview Answer

"How do you revoke a JWT that's already been issued?"

Stateless JWTs cannot be revoked natively — that is the fundamental tension. This system solves it with two independent layers:
Layer 1 — JTI Blacklisting
Every JWT carries a unique jti claim. The resource server checks Redis for blacklisted_jti:<jti> on every single request. If the key exists, the request is rejected regardless of token validity or expiry.
Layer 2 — User-Level Kill Switch
On refresh token reuse detection, a revoked_user:<email> key is set in Redis. This blocks all requests from that user instantly — including tokens whose JTIs have not yet been individually blacklisted.
The two-layer approach handles the race condition where an attacker generates new tokens faster than individual JTIs can be blacklisted.

