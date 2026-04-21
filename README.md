# OAuth2 Authorization Server with Refresh Token Reuse Detection

A production-grade OAuth2 Authorization Server built from scratch using **Spring Authorization Server**. This is not a tutorial implementation — it solves a real security problem that stateless JWT systems face in production:

> **How do you revoke a token that was designed to be unrevocable?**

## 🔗 Related Repositories
* [oauth2-gateway](https://github.com/YOUR_USERNAME/oauth2-gateway) — Spring Cloud Gateway with session management, rate limiting, and OIDC logout.
* [oauth2-resource-server](https://github.com/YOUR_USERNAME/oauth2-resource-server) — Protected API with JTI blacklist enforcement and role-based access control.

---

## 🛡️ The Core Problem
JWTs are stateless by design. Once issued, they are valid until expiry — you cannot invalidate them. This creates a real security gap:
1. A stolen refresh token can silently generate new access tokens indefinitely.
2. Logging out does not actually revoke tokens.
3. There is no native way to kill a user's session in a stateless system.

**This project solves all three problems.**

---

## 🏗️ Architecture
```text
Browser
  │
  ▼
Gateway (port 8081)
  ├── OAuth2 Login
  ├── Redis Session Management
  ├── Rate Limiting per User
  └── OIDC Logout
  │
  ├──► Auth Server (port 9000)
  │     ├── Issues JWT Access Tokens (5 min TTL)
  │     ├── Issues Refresh Tokens (7 day TTL)
  │     ├── Detects Refresh Token Reuse
  │     ├── Revokes All Sessions on Attack
  │     ├── MySQL → users, audit logs, OAuth2 records
  │     └── Redis → used refresh tokens, blacklisted JTIs
  │
  └──► Resource Server (port 8082)
        ├── Validates JWT signature
        ├── Checks JTI Blacklist in Redis
        ├── Checks User Revocation in Redis
        └── Role-Based Access Control
⚔️ What Happens During a Reuse Attack?This is the most important flow in the system. When a refresh token is stolen and used by an attacker:Step 1 — Legitimate user uses refresh tokenAuth server issues new access token + new refresh token (rotation).Old refresh token is hashed with SHA-256 and stored in Redis as used_rt:<hash>.Redis key stores the principal name with an 8-day TTL.Step 2 — Attacker uses the same old refresh tokenAuth server looks up used_rt: in Redis.Finds the principal name — reuse detected.Triggers Nuclear Revocation immediately.Step 3 — Nuclear Revocation FiresSets revoked_user:<email> in Redis — User-level kill switch.Queries all active authorizations from MySQL for this user.Extracts JTI from every active access token.Sets blacklisted_jti:<jti> in Redis for each token.Removes all authorizations from the database.Logs REFRESH_TOKEN_REUSE event to the audit table.Step 4 — Resource Server Enforces RevocationEvery incoming request checks Redis for blacklisted_jti:.Every incoming request checks Redis for revoked_user:.If either key exists → 401 Unauthorized immediately.📊 Redis Key StructureKey PatternValueTTLPurposeused_rt:<sha256>principal name8 daysMarks a rotated refresh token as usedrevoked_user:<email>"revoked"30 minUser-level kill switch after reuse attackblacklisted_jti:<id>"revoked"30 minIndividual access token blacklistspring:session:sessions:session data30 minGateway session store📝 Audit LogEvery auth event is persisted asynchronously to MySQL:Event TypeWhen It FiresTOKEN_ISSUEDEvery login and every refresh token useTOKEN_ROTATIONWhen refresh token is rotated (old → new)REFRESH_TOKEN_REUSEWhen a used refresh token is submitted again🛠️ Tech StackAuthorization Server: Spring Authorization Server 1.xGateway: Spring Cloud Gateway (WebFlux)Resource Server: Spring Security OAuth2 Resource ServerDatabase: MySQL 8Cache / Blacklist: Redis 7Infrastructure: Docker Compose🚀 Running Locally1. Start InfrastructureBashdocker-compose up -d
Starts MySQL on port 3307 and Redis on port 6380.2. Start ServicesRun the following in three separate terminals:Bash# Auth Server (Port 9000)
cd auth-server && mvn spring-boot:run

# Gateway (Port 8081)
cd gateway && mvn spring-boot:run

# Resource Server (Port 8082)
cd resource-server && mvn spring-boot:run
🎓 The Interview Answer"How do you revoke a JWT that's already been issued?"Stateless JWTs cannot be revoked natively. I solved it with a two-layer defense:JTI Blacklisting: Every JWT has a unique ID (jti). The Resource Server checks Redis for blacklisted_jti: on every request.User-Level Kill Switch: On reuse detection, a revoked_user: key is set in Redis. This blocks all requests from that user instantly, even if their specific token hasn't been individually blacklisted yet.
