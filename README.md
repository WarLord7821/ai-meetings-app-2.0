# AI Meeting Notes

Turn meeting transcripts into instant AI summaries and action items.

**Stack (v2 – re-platform):**
- **Backend:** Spring Boot 3 (Java 21) — REST API with JWT auth, OpenRouter-powered summaries
- **Frontend:** Angular 17+ (standalone SPA on vanilla CSS with the indigo/zinc palette)
- **Database:** MySQL 8 (local `dev` profile) / SQLite (production `prod` profile)
- **Hosting:** Oracle Cloud Always Free VM (API) + Netlify (SPA)

> ℹ️ The legacy Next.js codebase (Supabase + Prisma + Stripe) still lives in
> `src/`. The new re-platformed code lives in `backend/` and `frontend/`. The
> old Stripe billing was removed — users get a free daily limit of **3 AI
> summaries**.

---

## Project layout

```
ai-meeting-notes/
├── backend/                ← Spring Boot 3 REST API (Java 21, Maven)
│   └── src/main/java/com/ai/meetingnotes/
│       ├── config/         ← Spring Security + CORS
│       ├── controller/     ← /api/auth/*, /api/meetings, /api/users/me
│       ├── dto/            ← request/response records
│       ├── entity/         ← User, Meeting (JPA)
│       ├── repository/     ← Spring Data JPA repositories
│       ├── security/       ← JWT service + filter
│       └── service/        ← auth, meetings (OpenRouter integration)
├── frontend/               ← Angular 17+ SPA
│   └── src/app/
│       ├── core/           ← auth service, JWT interceptor, guard, HTTP client
│       ├── features/       ← home, login, signup, dashboard, meeting-detail
│       └── shared/         ← meeting-form/list/logout components + models
├── src/                    ← (legacy Next.js app, kept for reference)
└── implementation_plan.md  ← the architectural plan this repo follows
```

---

## Backend — Spring Boot

### Prerequisites

- **JDK 21** (e.g. Eclipse Adoptium Temurin 21)
- **Maven** (`mvn`) on your `PATH`
- Local **MySQL 8** server (for the `dev` profile)

### Database setup (local dev)

The `dev` profile uses MySQL and expects the connection settings from
`backend/src/main/resources/application-dev.properties`:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/ai_meeting_db?createDatabaseIfNotExist=true
spring.datasource.username=root
spring.datasource.password=root
```

Change the credentials to match your local MySQL before starting.

### Environment variables

Backend secrets are read from the environment (see the properties files):

| Variable        | Purpose                                   | Default (dev only)                     |
|-----------------|-------------------------------------------|----------------------------------------|
| `OPENROUTER_API_KEY` | OpenRouter API key for AI summaries | **required** in prod (dev has a fallback) |
| `OPENROUTER_MODEL` | OpenRouter model slug (prod override) | `google/gemma-4-31b-it:free` |
| `JWT_SECRET`    | Base64 secret signing JWTs (prod)         | dev profile ships a dev value          |
| `JWT_EXPIRATION`| Token lifetime in ms (prod override)     | `86400000` (24h)                       |

### Run locally

```bash
# terminal 1 — MySQL must be running: mysql -u root -p
# build & run with dev profile
cd backend
export OPENROUTER_API_KEY=your_openrouter_api_key
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

The API listens on **http://localhost:8080**.
### Main endpoints

| Method | Path                 | Auth   | Description                          |
|--------|----------------------|--------|--------------------------------------|
| POST   | `/api/auth/register` | public | Create account, returns JWT + user   |
| POST   | `/api/auth/login`    | public | Login, returns JWT + user            |
| GET    | `/api/auth/me`       | bearer | Current user (legacy alias)          |
| GET    | `/api/users/me`      | bearer | Current user                          |
| GET    | `/api/meetings`      | bearer | Paged list of my meetings             |
| GET    | `/api/meetings/all`  | bearer | Full list of my meetings              |
| POST   | `/api/meetings`      | bearer | Create meeting + AI summary          |
| GET    | `/api/meetings/{id}` | bearer | Single meeting detail                |

---

## Frontend — Angular

### Prerequisites

- **Node.js** + the project's Angular CLI package (`ng`)

```bash
cd frontend
npm install
ng serve           # dev server on http://localhost:4200
```

The Angular app reads `src/environments/environment.ts` for the API base URL.
It defaults to `http://localhost:8080` (local backend). A production build
automatically swaps in `environment.prod.ts` via `angular.json`
`fileReplacements`.

### Routing

| Path                      | Component      | Guard                              |
|---------------------------|----------------|------------------------------------|
| `/`                       | home           | public                             |
| `/login`                  | login          | public                             |
| `/signup`                 | signup         | public                             |
| `/dashboard`              | dashboard      | **auth** (authGuard `canActivate`) |
| `/dashboard/meetings/:id` | meeting-detail | **auth**                           |

Auth flow: `AuthService` keeps the JWT in `localStorage`. The API interceptor
attaches `Authorization: Bearer <token>` to every request; on a `401` the
session is cleared and the user is sent back to `/login`.

---

## Local verification

1. Start local MySQL, then run the backend (dev profile, see above).
2. In a second terminal, run `ng serve` in `frontend/`.
3. Open http://localhost:4200.
4. Register an account → you land on `/dashboard`.
5. Paste a transcript (≥ 20 chars) → the backend calls OpenRouter and returns a
   summary + action items.
6. Reload `/dashboard` → the meeting appears in *Past Meetings*.
7. Open a meeting card → detail page shows summary, action items and transcript.

---

## Production hosting

See **[HOSTING.md](HOSTING.md)** for the step-by-step write-up:

1. Provision an **Oracle Cloud Always Free** Ampere A1 VM.
2. Install **Java 21**, build the backend JAR, and run it with the `prod`
   profile (SQLite file on the persistent disk).
3. Open **port 8080** in the VCN security list.
4. Deploy the **Angular** app to **Netlify**, pointing at the Oracle VM IP.


>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
Confirmed — `test78@gmail.com` is `PRO` / `ACTIVE` with real Stripe customer/subscription IDs saved. It worked.

## What the actual issue was

**Root cause: two different Stripe accounts.**

Your `stripe` CLI was logged into one Stripe account (`acct_1Tu8dKRqiRXG9fQQ`), but your backend's `application-dev.properties` had a secret key (`sk_test_51UI3qF...`) belonging to a **different** Stripe account (`acct_1UI3qFRplVguLDgI`, "meow sandbox"). Every checkout session your backend created was on account B, but `stripe listen` was only ever listening for webhooks on account A. Stripe accounts are fully isolated — a webhook can never cross from one account to another, no matter how correct the code is. So:

- Purchases completed fine (Stripe took the test payment on account B).
- The webhook event existed only on account B, which nothing was listening to.
- `stripe events resend evt_...` failed with `resource_missing` because that event ID lived on account B while the CLI was querying account A.
- The DB never updated because the webhook handler was never invoked.

**Contributing issues that made it harder to diagnose**, all now cleaned up:
1. **Duplicate processes**: two `stripe listen` sessions and two Spring Boot instances were running simultaneously at one point, from restarting without killing old ones. This added noise (stale zombie process holding old code, uncertainty about which process actually owned port 8080).
2. **A stale webhook endpoint registered in the Stripe dashboard** pointing at an old Vercel deployment (`ai-meeting-notes-teal-iota.vercel.app`), left over from before this app was migrated from Next.js to Spring Boot + Angular. I deleted it — it was a decoy that made the dashboard show "received" events that had nothing to do with your local setup.

## Precautions for next time

1. **Before starting work each session, check nothing stale is already running:**
   ```powershell
   Get-Process | Where-Object {$_.ProcessName -match 'stripe|java'} | Select ProcessName,Id
   ```
   If you see leftovers from a previous session, kill them before starting fresh — don't just layer a new `stripe listen`/`mvn spring-boot:run` on top.

2. **Always confirm the CLI account matches your app's secret key** before testing:
   ```
   stripe config --list
   ```
   Check the `[default]` profile's `account_id`/`test_mode_api_key` prefix against `stripe.secret-key` in `application-dev.properties`. If they ever diverge (e.g. after `stripe login` gets re-run for a different project), webhooks will silently stop working exactly like this.

3. **Every time you start `stripe listen` fresh, it may print a new `whsec_...` secret** — always re-paste it into `application-dev.properties` and restart Spring Boot. The startup log line `Stripe webhook handler ready — secret prefix: whsec_...` is there specifically so you can eyeball-match it against the CLI's printed secret.

4. **Standard clean startup order** going forward:
   ```powershell
   # 1. kill any old stripe/java processes first
   # 2. confirm account:
   stripe config --list
   # 3. start listener, note the whsec_... it prints
   stripe listen --forward-to localhost:8080/api/webhooks/stripe
   # 4. paste that secret into application-dev.properties if different
   # 5. start backend
   mvn spring-boot:run -Dspring-boot.run.profiles=dev
   # 6. start frontend
   npm start
   ```

5. **Don't trust the frontend's green "You're now on Pro!" banner as proof of success** — it just reflects the `?success=subscription` URL param Stripe redirects with, regardless of whether the webhook actually processed. The real signal is the plan badge below it (driven by `/api/billing/status`) or checking the DB directly.

One more thing worth doing soon, separate from this: your `application-dev.properties` has real-looking secret keys (Stripe, JWT, OpenRouter) committed in plaintext. Want me to check if that file is tracked in git and help get it ignored/rotated?
