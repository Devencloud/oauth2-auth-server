<div align="center">
🔐 OAuth2 Authorization Server
Refresh Token Reuse Detection & Nuclear Session Revocation
<br/>
Show Image
Show Image
Show Image
Show Image
Show Image
Show Image
<br/>

Production-grade OAuth2 Authorization Server built from scratch using Spring Authorization Server.
This is not a tutorial — it solves a real security problem that stateless JWT systems face in production.

<br/>
"How do you revoke a token that was designed to be unrevocable?"
<br/>
</div>

📦 Related Repositories
RepositoryDescriptionoauth2-gatewaySpring Cloud Gateway — session management, rate limiting, OIDC logoutoauth2-resource-serverProtected API — JTI blacklist enforcement, role-based access control

⚠️ The Core Problem
JWTs are stateless by design. Once issued, they are valid until expiry — there is no native way to invalidate them. This creates a serious security gap in any token-based system:
ProblemImpact🔑 Stolen refresh tokenSilently generates new access tokens indefinitely🚪 LogoutDoes not actually revoke issued tokens🌐 Stateless architectureNo built-in mechanism to kill a live session
This project solves all three.

🏗️ System Architecture
                        ┌─────────┐
                        │ Browser │
                        └────┬────┘
                             │
                             ▼
              ┌──────────────────────────┐
              │        GATEWAY           │  :8081
              │  OAuth2 Login            │
              │  Redis Session Store     │
              │  Rate Limiting (5 rps)   │
              │  OIDC Logout             │
              └──────────┬───────────────┘
                         │
           ┌─────────────┴──────────────┐
           │                            │
           ▼                            ▼
┌─────────────────────┐    ┌──────────────────────────┐
│    AUTH SERVER       │    │     RESOURCE SERVER       │
│      :9000           │    │         :8082             │
│                      │    │                           │
│  Issues JWTs         │    │  Validates JWT signature  │
│  5 min access TTL    │    │  Checks JTI blacklist     │
│  7 day refresh TTL   │    │  Checks user revocation   │
│  Refresh rotation    │    │  Role-based access (RBAC) │
│  Reuse detection     │    │                           │
│  Nuclear revocation  │    └──────────────────────────┘
│                      │
│  MySQL  <->  Redis   │
└──────────────────────┘
ComponentPortStorageKey ResponsibilitiesGateway8081RedisOAuth2 login, session management, rate limiting, OIDC logoutAuth Server9000MySQL + RedisJWT issuance, refresh rotation, reuse detection, revocationResource Server8082Redis (read)JWT validation, blacklist enforcement, RBAC

🚨 Refresh Token Reuse Attack — Full Flow

This is the most important flow in the system.
When a refresh token is stolen and replayed by an attacker, here is exactly what happens:

<br/>
Step 1 — Legitimate User Rotates Their Token

Auth server issues a new access token + new refresh token
Old refresh token is SHA-256 hashed and stored in Redis as used_rt:<hash>
Redis key holds the principal name with an 8-day TTL

Step 2 — Attacker Replays the Old Refresh Token

Auth server looks up used_rt:<hash> in Redis
Finds the principal name associated with it
Reuse detected → nuclear revocation triggers immediately

Step 3 — ☢️ Nuclear Revocation Fires

revoked_user:<email> set in Redis (user-level kill switch)
Queries all active sessions from MySQL for this user
Extracts the jti from every active access token
blacklisted_jti:<jti> set in Redis for each token individually
All authorizations purged from the database
REFRESH_TOKEN_REUSE event written to the audit log

Step 4 — Resource Server Enforces the Revocation

Every request checks Redis for blacklisted_jti:<jti>
Every request checks Redis for revoked_user:<email>
Either key present → 401 Unauthorized immediately
Token never reaches the controller

🗂️ Redis State After a Reuse Attack
revoked_user:user1@gmail.com          →  "revoked"          TTL: 30 min
blacklisted_jti:3ab9977f-c21f-...     →  "revoked"          TTL: 30 min
blacklisted_jti:2478c7f8-a5c2-...     →  "revoked"          TTL: 30 min
used_rt:7d5643b845d071b89f84...       →  "user1@gmail.com"  TTL: 8 days

✅ Features
Authorization Server
FeatureDetailOAuth2 Authorization Code FlowStandard OAuth2 flow for user authenticationRefresh token rotationNew refresh token issued on every use, old one invalidatedReuse detectionFull session revocation triggered automatically on attackJTI blacklistingPer-token invalidation stored in RedisUser-level kill switchInstant blanket revocation via revoked_user:<email>RSA-signed JWTsRS256 via JKS keystoreBCrypt-hashed secretsSecure client secret storageCustom JWT claimsemail and roles embedded in every access tokenAsync audit loggingAll auth events written to MySQL off the request thread
Gateway
FeatureDetailSpring Cloud GatewayFull OAuth2 login integrationRedis-backed sessions30-minute sliding timeoutRate limiting5 req/sec per user, burst of 10Token relayAccess token forwarded to downstream servicesOIDC-compliant logoutClears gateway session and auth server SSO session
Resource Server
FeatureDetailJWT signature validationVerified against auth server's RSA public keyJTI blacklist checkOn every request — before any business logicUser revocation checkOn every request — instant kill switch enforcementRole-based access controlROLE_USER and ROLE_ADMIN enforced at endpoint level

🗃️ Redis Key Reference
Key PatternValueTTLPurposeused_rt:<sha256_hash>principal name8 daysMarks a rotated refresh token as consumedrevoked_user:<email>"revoked"30 minUser-level kill switch — blocks all requestsblacklisted_jti:<jti>"revoked"30 minPer-token blacklist entryspring:session:sessions:<id>session blob30 minGateway session store via Spring Session

📋 Audit Log Events
Every auth event is persisted asynchronously to MySQL — zero impact on request latency:
EventTriggerTOKEN_ISSUEDEvery successful login and every refresh token useTOKEN_ROTATIONWhen a refresh token is rotated (old → new recorded)REFRESH_TOKEN_REUSEWhen a previously consumed refresh token is submitted again

⚙️ Token Configuration
ParameterValueAccess Token TTL5 minutesRefresh Token TTL7 daysRefresh Token ReuseDisabled — rotation enforcedSigning AlgorithmRS256 (RSA asymmetric)Client Authenticationclient_secret_basic

🛠️ Tech Stack
LayerTechnologyAuthorization ServerSpring Authorization Server 1.xGatewaySpring Cloud Gateway (WebFlux / reactive)Resource ServerSpring Security OAuth2 Resource ServerDatabaseMySQL 8Cache / BlacklistRedis 7Session StoreSpring Session with RedisToken FormatJWT — RS256 signedInfrastructureDocker Compose

🚀 Running Locally
Prerequisites: Java 21 · Maven · Docker
Step 1 — Start Infrastructure
bashdocker-compose up -d

Starts MySQL on :3307 and Redis on :6380

Step 2 — Auth Server
bashcd auth-server
cp src/main/resources/application.properties.template src/main/resources/application.properties
mvn spring-boot:run
Step 3 — Gateway
bashcd gateway
cp src/main/resources/application.yml.template src/main/resources/application.yml
mvn spring-boot:run
Step 4 — Resource Server
bashcd resource-server
cp src/main/resources/application.properties.template src/main/resources/application.properties
mvn spring-boot:run
Step 5 — Open the App
Navigate to http://localhost:8081/api/me — you will be redirected to the login page automatically.

🔌 API Endpoints
EndpointMethodAccessDescription/api/meGETAuthenticatedReturns current user info decoded from JWT/api/admin/dashboardGETROLE_ADMINAdmin-only protected endpoint/oauth2/tokenPOSTClient credentialsToken issuance endpoint/oauth2/introspectPOSTClient credentialsToken introspection/oauth2/revokePOSTClient credentialsToken revocation/.well-known/openid-configurationGETPublicOIDC discovery document/logoutGETAuthenticatedClears gateway session and SSO session

🗺️ Production Roadmap
EnhancementRationaleKeycloak federationExternal IdP support — Google, corporate SSOHashiCorp VaultReplace hardcoded secrets with dynamic injectionmTLSMutual TLS for all service-to-service callsPKCE enforcementAlready live for public clients — enforce globallyScope-based access controlFine-grained permissions: read, write, adminOWASP ZAP scanningAutomated vulnerability scans in CI/CD pipeline

💬 The Interview Answer

"How do you revoke a JWT that's already been issued?"

Stateless JWTs cannot be revoked natively — that is the fundamental tension. This system resolves it with two independent enforcement layers:
🔹 Layer 1 — JTI Blacklisting
Every JWT carries a unique jti claim. The resource server checks Redis for blacklisted_jti:<jti> on every single request. If the key exists, the request is rejected — regardless of token validity or remaining TTL.
🔹 Layer 2 — User-Level Kill Switch
On refresh token reuse detection, revoked_user:<email> is written to Redis. This blocks all requests from that user instantly — including tokens whose JTIs have not yet been individually blacklisted.

The two-layer approach handles the race condition where an attacker generates new tokens faster than individual JTIs can be blacklisted. The user-level key acts as a net that catches everything.
