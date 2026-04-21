# OAuth2 Authorization Server with Refresh Token Reuse Detection

A production-grade OAuth2 Authorization Server built from scratch using Spring Authorization Server. This is not a tutorial implementation — it solves real security problems that stateless JWT systems face in production: how do you revoke a token that was designed to be unrevocable?

---

## Related Repositories

- **oauth2-gateway** — Spring Cloud Gateway with session management, rate limiting, and OIDC logout  
- **oauth2-resource-server** — Protected API with JTI blacklist enforcement and role-based access control  

---

## The Core Problem This Solves

JWTs are stateless by design. Once issued, they are valid until expiry — you cannot invalidate them. This creates a real security gap:

- A stolen refresh token can silently generate new access tokens indefinitely  
- Logging out does not actually revoke tokens  
- There is no native way to kill a user's session in a stateless system  

This project solves all three problems.

---

## Architecture
Browser
│
▼
Gateway (8081) ──── Session Management (Redis)
│ Rate Limiting per User
│ OIDC Logout
│
├──► Auth Server (9000) ──── MySQL (users, audit logs, OAuth2 records)
│ │ Redis (used refresh tokens, blacklisted JTIs)
│ │
│ └── Issues JWT Access Tokens (5 min TTL)
│ Issues Refresh Tokens (7 day TTL)
│ Detects Refresh Token Reuse
│ Revokes All Sessions on Attack
│
└──► Resource Server (8082) ── Validates JWT
Checks JTI Blacklist (Redis)
Checks User Revocation (Redis)
Role-Based Access Control

---

## What Happens During a Reuse Attack

This is the most important flow in the system. When a refresh token is stolen and used by an attacker:

### Step 1 — Legitimate user uses refresh token

- Auth server issues new access token + new refresh token (rotation)  
- Old refresh token is hashed (SHA-256) and stored in Redis as `used_rt:<hash>`  
- Redis key stores the principal name with 8-day TTL  

### Step 2 — Attacker uses the same (old) refresh token

- Auth server looks up `used_rt:<hash>` in Redis  
- Finds the principal name — reuse detected  
- Triggers nuclear revocation immediately  

### Step 3 — Nuclear revocation

- Sets `revoked_user:<email>` in Redis — user-level kill switch  
- Queries all active sessions from MySQL for this user  
- Extracts JTI from every active access token  
- Sets `blacklisted_jti:<jti>` in Redis for each token  
- Removes all authorizations from database  
- Logs `REFRESH_TOKEN_REUSE` event to audit table  

### Step 4 — Enforcement on Resource Server

- Every incoming request checks Redis for `blacklisted_jti:<jti>`  
- Every incoming request checks Redis for `revoked_user:<email>`  
- If either key exists → **401 Unauthorized immediately**  
- Token never reaches the controller  

### Redis state after an attack:
revoked_user:user1@gmail.com → "revoked" (30 min TTL)
blacklisted_jti:3ab9977f-c21f-... → "revoked" (30 min TTL)
blacklisted_jti:2478c7f8-a5c2-... → "revoked" (30 min TTL)
used_rt:7d5643b845d071b89f84... → "user1@gmail.com
" (8 day TTL)

---

## Features

### Authorization Server

- OAuth2 Authorization Code Flow  
- Refresh token rotation — new refresh token issued on every use  
- Refresh token reuse detection with automatic full session revocation  
- JTI (JWT ID) blacklisting in Redis for individual token invalidation  
- User-level kill switch in Redis for instant full revocation  
- RSA-signed JWTs using a JKS keystore  
- BCrypt-hashed client secrets  
- Custom JWT claims — email and roles embedded in access token  
- Async audit logging of all auth events to MySQL  

---

### Gateway

- Spring Cloud Gateway with OAuth2 login  
- Redis-backed session management (30 min timeout)  
- Rate limiting per authenticated user (5 req/sec, burst 10)  
- Token relay to downstream services  
- OIDC-compliant logout — clears gateway session and SSO session on auth server  

---

### Resource Server

- JWT validation using auth server's public key (RSA)  
- JTI blacklist check on every request via Redis  
- User-level revocation check on every request via Redis  
- Role-based access control — ROLE_USER and ROLE_ADMIN  

---

## Redis Key Structure

| Key Pattern | Value | TTL | Purpose |
|------------|------|-----|--------|
| used_rt:<sha256_hash> | principal name | 8 days | Marks a rotated refresh token as used |
| revoked_user:<email> | "revoked" | 30 min | User-level kill switch after reuse attack |
| blacklisted_jti:<jti> | "revoked" | 30 min | Individual access token blacklist |
| spring:session:sessions:<id> | session data | 30 min | Gateway session store |

---

## Audit Log

Every auth event is persisted asynchronously to MySQL:

| Event Type | When It Fires |
|-----------|--------------|
| TOKEN_ISSUED | Every login and every refresh token use |
| TOKEN_ROTATION | When refresh token is rotated |
| REFRESH_TOKEN_REUSE | When a used refresh token is submitted again |

---

## Token Configuration

| Parameter | Value |
|----------|------|
| Access Token TTL | 5 minutes |
| Refresh Token TTL | 7 days |
| Refresh Token Reuse | Disabled (rotation enforced) |
| Signing Algorithm | RS256 (RSA) |
| Client Authentication | client_secret_basic |

---

## Tech Stack

| Layer | Technology |
|------|-----------|
| Authorization Server | Spring Authorization Server |
| Gateway | Spring Cloud Gateway |
| Resource Server | Spring Security OAuth2 Resource Server |
| Database | MySQL 8 |
| Cache / Blacklist | Redis 7 |
| Session Store | Spring Session Redis |
| Token Format | JWT (RS256) |
| Infrastructure | Docker Compose |

---

## Running Locally

### Prerequisites

- Java 21  
- Maven  
- Docker  

---

### Step 1 — Start infrastructure

```bash
docker-compose up -d
Step 2 — Start Auth Server
cd auth-server
cp src/main/resources/application.properties.template src/main/resources/application.properties
# Fill in your values
mvn spring-boot:run
Step 3 — Start Gateway
cd gateway
cp src/main/resources/application.yml.template src/main/resources/application.yml
# Fill in your values
mvn spring-boot:run
Step 4 — Start Resource Server
cd resource-server
cp src/main/resources/application.properties.template src/main/resources/application.properties
mvn spring-boot:run
Step 5 — Access the application

Go to:
👉 http://localhost:8081/api/me

API Endpoints
Endpoint	Method	Access	Description
/api/me	GET	Authenticated	Returns current user
/api/admin/dashboard	GET	ROLE_ADMIN	Admin endpoint
/oauth2/token	POST	Client	Token endpoint
/oauth2/introspect	POST	Client	Token introspection
/oauth2/revoke	POST	Client	Token revocation
/.well-known/openid-configuration	GET	Public	OIDC config
/logout	GET	Authenticated	Logout
What I Would Add in Production
Keycloak federation
HashiCorp Vault
mTLS
PKCE enforcement
Scope-based access control
OWASP ZAP scanning
The Interview Answer

"How do you revoke a JWT that's already been issued?"

Stateless JWTs cannot be revoked natively — that's the fundamental tension. I solved it with two layers:

JTI blacklisting — resource server checks Redis on every request
User-level kill switch — blocks all tokens instantly on reuse detection

This handles race conditions where attackers generate tokens faster than blacklist updates.

---

