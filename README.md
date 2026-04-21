[README.md](https://github.com/user-attachments/files/26930556/README.md)
# 🔐 OAuth2 Authorization Server — Refresh Token Reuse Detection

> **Production-grade OAuth2 Authorization Server built from scratch using Spring Authorization Server.**
> This is not a tutorial — it solves a real security problem stateless JWT systems face in production.
>
> *"How do you revoke a token that was designed to be unrevocable?"*

---

## 📦 Related Repositories

| Repository | Description |
|---|---|
| [`oauth2-gateway`](https://github.com/yourusername/oauth2-gateway) | Spring Cloud Gateway — session management, rate limiting, OIDC logout |
| [`oauth2-resource-server`](https://github.com/yourusername/oauth2-resource-server) | Protected API — JTI blacklist enforcement, role-based access control |

---

## ⚠️ The Core Problem

JWTs are stateless by design. Once issued, they are valid until expiry — there is no native way to invalidate them.

| Problem | Impact |
|---|---|
| Stolen refresh token | Silently generates new access tokens indefinitely |
| Logout | Does not actually revoke issued tokens |
| Stateless architecture | No built-in mechanism to kill a live session |

**This project solves all three.**

---

## 🏗️ System Architecture

```
                     ┌─────────┐
                     │ Browser │
                     └────┬────┘
                          │
                          ▼
           ┌──────────────────────────┐
           │          GATEWAY         │  :8081
           │  OAuth2 Login            │
           │  Redis Session Store     │
           │  Rate Limiting (5 rps)   │
           │  OIDC Logout             │
           └──────────┬───────────────┘
                      │
        ┌─────────────┴─────────────┐
        │                           │
        ▼                           ▼
┌──────────────────┐    ┌───────────────────────┐
│   AUTH SERVER    │    │    RESOURCE SERVER    │
│     :9000        │    │        :8082          │
│                  │    │                       │
│  Issues JWTs     │    │  Validates JWT sig    │
│  5 min TTL       │    │  Checks JTI blacklist │
│  7 day refresh   │    │  Checks revocation    │
│  Rotation        │    │  RBAC enforcement     │
│  Reuse detect    │    │                       │
│  Revocation      │    └───────────────────────┘
│  MySQL + Redis   │
└──────────────────┘
```

| Component | Port | Storage | Responsibilities |
|---|---|---|---|
| **Gateway** | `8081` | Redis | OAuth2 login, sessions, rate limiting, OIDC logout |
| **Auth Server** | `9000` | MySQL + Redis | JWT issuance, refresh rotation, reuse detection, revocation |
| **Resource Server** | `8082` | Redis (read) | JWT validation, blacklist + revocation enforcement, RBAC |

---

## 🚨 Refresh Token Reuse Attack — Full Flow

> When a refresh token is stolen and replayed by an attacker, this is exactly what happens:

### Step 1 — Legitimate User Rotates Their Token

- Auth server issues a **new access token** + **new refresh token**
- Old refresh token is `SHA-256` hashed → stored in Redis as `used_rt:<hash>`
- Key holds the principal name with an **8-day TTL**

### Step 2 — Attacker Replays the Old Token

- Auth server looks up `used_rt:<hash>` in Redis
- Finds the principal name — **reuse detected**
- Nuclear revocation triggers immediately

### Step 3 — ☢️ Nuclear Revocation

- `revoked_user:<email>` set in Redis — user-level kill switch
- All active sessions queried from MySQL for this user
- `jti` extracted from every active access token
- `blacklisted_jti:<jti>` set in Redis for each token individually
- All authorizations purged from the database
- `REFRESH_TOKEN_REUSE` event written to the audit log

### Step 4 — Resource Server Enforces It

- Every request checks `blacklisted_jti:<jti>` in Redis
- Every request checks `revoked_user:<email>` in Redis
- Either key exists → `401 Unauthorized`
- **Token never reaches the controller**

### Redis State After a Reuse Attack

```
revoked_user:user1@gmail.com       → "revoked"           TTL: 30 min
blacklisted_jti:3ab9977f-c21f-...  → "revoked"           TTL: 30 min
blacklisted_jti:2478c7f8-a5c2-...  → "revoked"           TTL: 30 min
used_rt:7d5643b845d071b89f84...    → "user1@gmail.com"   TTL: 8 days
```

---

## ✅ Features

### Authorization Server

| Feature | Detail |
|---|---|
| OAuth2 Authorization Code Flow | Standard OAuth2 flow for user authentication |
| Refresh token rotation | New refresh token issued on every use, old one invalidated |
| Reuse detection | Full session revocation triggered automatically on attack |
| JTI blacklisting | Per-token invalidation stored in Redis |
| User-level kill switch | Instant blanket revocation via `revoked_user:<email>` |
| RSA-signed JWTs | RS256 via JKS keystore |
| BCrypt-hashed secrets | Secure client secret storage |
| Custom JWT claims | `email` and `roles` embedded in every access token |
| Async audit logging | All auth events written to MySQL off the request thread |

### Gateway

| Feature | Detail |
|---|---|
| Spring Cloud Gateway | Full OAuth2 login integration |
| Redis-backed sessions | 30-minute sliding timeout |
| Rate limiting | 5 req/sec per user, burst of 10 |
| Token relay | Access token forwarded to downstream services |
| OIDC-compliant logout | Clears gateway session and auth server SSO session |

### Resource Server

| Feature | Detail |
|---|---|
| JWT signature validation | Verified against auth server's RSA public key |
| JTI blacklist check | On every request, before any business logic |
| User revocation check | On every request, instant kill switch enforcement |
| Role-based access control | `ROLE_USER` and `ROLE_ADMIN` enforced at endpoint level |

---

## 🗃️ Redis Key Reference

| Key Pattern | Value | TTL | Purpose |
|---|---|---|---|
| `used_rt:<sha256_hash>` | principal name | 8 days | Marks a rotated refresh token as consumed |
| `revoked_user:<email>` | `"revoked"` | 30 min | User-level kill switch — blocks all requests |
| `blacklisted_jti:<jti>` | `"revoked"` | 30 min | Per-token blacklist entry |
| `spring:session:sessions:<id>` | session blob | 30 min | Gateway session store via Spring Session |

---

## 📋 Audit Log

Every auth event is persisted **asynchronously** to MySQL — zero impact on request latency:

| Event | Trigger |
|---|---|
| `TOKEN_ISSUED` | Every successful login and every refresh token use |
| `TOKEN_ROTATION` | When a refresh token is rotated (old → new) |
| `REFRESH_TOKEN_REUSE` | When a previously consumed refresh token is replayed |

---

## ⚙️ Token Configuration

| Parameter | Value |
|---|---|
| Access Token TTL | 5 minutes |
| Refresh Token TTL | 7 days |
| Refresh Token Reuse | Disabled — rotation enforced |
| Signing Algorithm | RS256 (RSA asymmetric) |
| Client Authentication | `client_secret_basic` |

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Authorization Server | Spring Authorization Server 1.x |
| Gateway | Spring Cloud Gateway (WebFlux) |
| Resource Server | Spring Security OAuth2 Resource Server |
| Database | MySQL 8 |
| Cache / Blacklist | Redis 7 |
| Session Store | Spring Session with Redis |
| Token Format | JWT — RS256 signed |
| Infrastructure | Docker Compose |

---

## 🚀 Running Locally

**Prerequisites:** Java 21, Maven, Docker

### Step 1 — Start Infrastructure

```bash
docker-compose up -d
```

> Starts MySQL on `:3307` and Redis on `:6380`

### Step 2 — Auth Server

```bash
cd auth-server
cp src/main/resources/application.properties.template src/main/resources/application.properties
mvn spring-boot:run
```

### Step 3 — Gateway

```bash
cd gateway
cp src/main/resources/application.yml.template src/main/resources/application.yml
mvn spring-boot:run
```

### Step 4 — Resource Server

```bash
cd resource-server
cp src/main/resources/application.properties.template src/main/resources/application.properties
mvn spring-boot:run
```

### Step 5 — Open the App

Navigate to `http://localhost:8081/api/me` — you will be redirected to login automatically.

---

## 🔌 API Endpoints

| Endpoint | Method | Access | Description |
|---|---|---|---|
| `/api/me` | `GET` | Authenticated | Returns current user info decoded from JWT |
| `/api/admin/dashboard` | `GET` | `ROLE_ADMIN` | Admin-only protected endpoint |
| `/oauth2/token` | `POST` | Client credentials | Token issuance |
| `/oauth2/introspect` | `POST` | Client credentials | Token introspection |
| `/oauth2/revoke` | `POST` | Client credentials | Token revocation |
| `/.well-known/openid-configuration` | `GET` | Public | OIDC discovery document |
| `/logout` | `GET` | Authenticated | Clears gateway and SSO session |

---

## 🗺️ Production Roadmap

| Enhancement | Rationale |
|---|---|
| **Keycloak federation** | External IdP support — Google, corporate SSO |
| **HashiCorp Vault** | Replace hardcoded secrets with dynamic injection |
| **mTLS** | Mutual TLS for service-to-service calls |
| **PKCE enforcement** | Already live for public clients — enforce globally |
| **Scope-based access control** | Fine-grained permissions: `read`, `write`, `admin` |
| **OWASP ZAP scanning** | Automated vulnerability scans in CI/CD |

---

## 💬 The Interview Answer

> **"How do you revoke a JWT that's already been issued?"**

Stateless JWTs cannot be revoked natively — that is the fundamental tension. This system resolves it with two independent enforcement layers:

**Layer 1 — JTI Blacklisting**

Every JWT carries a unique `jti` claim. The resource server checks Redis for `blacklisted_jti:<jti>` on every request. If the key exists, the request is rejected — regardless of token validity or remaining TTL.

**Layer 2 — User-Level Kill Switch**

On refresh token reuse detection, `revoked_user:<email>` is written to Redis. This blocks all requests from that user instantly — including tokens whose JTIs have not yet been individually blacklisted.

> The two-layer approach handles the race condition where an attacker generates new tokens faster than individual JTIs can be blacklisted. The user-level key acts as a net that catches everything.
