# AgentNotes AI

A full-stack meeting notes SaaS. Paste a raw transcript ? get a structured AI summary. Ask natural-language questions about your meeting history via a RAG chatbot. Both features are also available through an autonomous WhatsApp agent powered by OpenClaw.

---

## Stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 3.5, Java 25, Maven |
| Frontend | Angular 18 (standalone components, signals) |
| Auth | JWT (stateless, HMAC-SHA signed) |
| LLM | OpenRouter (chat completions + embeddings) |
| User/billing DB | MySQL 8 (dev) / SQLite (prod) |
| Meeting storage | Flat JSON files (no DB table) |
| Vector store | Flat JSON + in-memory cosine similarity |
| Payments | Stripe Checkout + webhooks |
| WhatsApp agent | OpenClaw (self-hosted agent gateway) |
| Frontend hosting | Netlify |
| API hosting | Oracle Cloud Always Free Ampere A1 VM |

---

## Project layout

```
ai-meeting-notes/
+-- backend/
¦   +-- src/main/java/com/ai/meetingnotes/
¦       +-- config/          # SecurityConfig, StripeConfig, StringListJsonConverter
¦       +-- controller/      # AuthController, MeetingController, ChatController,
¦       ¦                    # BillingController, StripeWebhookController, UserController,
¦       ¦                    # GlobalExceptionHandler
¦       +-- dto/             # AuthRequest/Response, MeetingRequest/Response,
¦       ¦                    # BillingStatusResponse, ChatRequest/Response
¦       +-- entity/          # User (JPA — meetings are stored in flat files, not JPA)
¦       +-- repository/      # UserRepository, MeetingRepository
¦       +-- security/        # JwtService, JwtAuthenticationFilter
¦       +-- service/         # AuthService (via UserDetailsServiceImpl), MeetingService,
¦                            # MeetingFileService, RagService, EmbeddingStoreService,
¦                            # OpenRouterClient
+-- frontend/
¦   +-- src/app/
¦       +-- core/            # AuthService, AuthInterceptor, AuthGuard,
¦       ¦                    # MeetingsService, BillingService, ChatService
¦       +-- features/        # home, login, signup, dashboard, meeting-detail, billing
¦       +-- shared/          # MeetingFormComponent, MeetingListComponent,
¦                            # LogoutButtonComponent, ChatPanelComponent
¦                            # meeting.model, billing.model, chat.model, user.model
+-- openclaw-workspace/
    +-- AGENTS.md            # LLM playbook for the WhatsApp agent
    +-- scripts/             # agent-login.{ps1,sh}, agent-create-meeting.{ps1,sh},
                             # agent-ask.{ps1,sh}
```

---

## Architecture

Two independent client surfaces share one Spring Boot API:

```
Angular SPA  -----------------------------+
                                          ?
OpenClaw WhatsApp agent ---------? Spring Boot REST API
                                          ¦
                          +---------------+----------------+
                          ?               ?                 ?
                    MySQL/SQLite    JSON flat files    OpenRouter
                  (users, billing)  (meetings.json,   (chat + embeddings)
                                    embeddings.json)
                                          ¦
                                          ?
                                       Stripe
                                   (checkout + webhooks)
```

The WhatsApp agent is not a separate backend — it is an LLM-driven client of the same REST API, which is why RAG chat works identically on both surfaces without additional code.

---

## API endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | public | Create account, returns JWT |
| POST | `/api/auth/login` | public | Login, returns JWT |
| GET | `/api/users/me` | bearer | Current user info |
| GET | `/api/meetings` | bearer | Paginated list of meetings |
| GET | `/api/meetings/all` | bearer | Full list of meetings |
| POST | `/api/meetings` | bearer | Create meeting + AI summary |
| GET | `/api/meetings/{id}` | bearer | Single meeting detail |
| POST | `/api/chat` | bearer | RAG chatbot — ask a question |
| GET | `/api/billing/status` | bearer | Current plan + credit balance |

---

## Auth & security

- JWT signed with HMAC-SHA. Claims include `planTier` and `subscriptionStatus` so plan checks don't require a DB hit on most paths.
- `JwtAuthenticationFilter` validates signature + expiry on every request. Expired/invalid tokens fail closed.
- **WhatsApp Pro gate — two layers:**
  1. The agent itself won't proceed past login if the account is not Pro (conversation-level gate via `AGENTS.md`).
  2. The API enforces `planTier == PRO && subscriptionStatus == ACTIVE` when it detects the `X-Agent-Channel: whatsapp` request header — bypassing the agent and scripting the API directly won't work.
- Summary-credit decrements use `UPDATE ... WHERE summaryCredits > 0` (atomic, no read-then-write race).

---

## Meeting summarization

**Flow:** `POST /api/meetings` ? quota/credit check ? OpenRouter (JSON mode) ? parse 7 fields ? write to `meetings.json` + `meetings.md` ? generate embedding ? upsert into `embeddings.json` ? `201`

**7 fields extracted per transcript in a single LLM call:**
`summary`, `objectives`, `keyPoints`, `decisions`, `outcomes`, `actionItems`, `nextSteps` / `pendingDiscussions`

The prompt enforces `response_format: json_object` and requires every field to be an array (empty, never null). A defensive `extractJsonObject` helper strips any stray text the model may add before `{` or after `}`.

---

## Storage design

| Store | What lives there | Why |
|---|---|---|
| MySQL / SQLite | `users` table (plan, credits, Stripe IDs) | Relational integrity required for billing |
| `meetings.json` | All meetings keyed by UUID, filtered by `userEmail` at read time | Zero infrastructure; copy two files to migrate |
| `meetings.md` | Append-only human-readable log of the same data | Easy manual inspection / grep |
| `embeddings.json` | `{meetingId, userEmail, embedding[1536]}` per meeting | One file, no vector DB needed at this scale |

All file I/O in `MeetingFileService` and `EmbeddingStoreService` is `synchronized` to prevent interleaved writes from concurrent requests.

---

## RAG chatbot (`POST /api/chat`)

### Indexing (runs once per meeting, immediately after save)

1. Build index text: `title + date + summary + all 7 structured fields`
2. Call OpenRouter `/embeddings` with model `openai/text-embedding-3-small` ? 1536-dim vector
3. Upsert `{meetingId, userEmail, embedding}` into `embeddings.json`

One embedding per whole meeting (not chunked). Summaries are short enough (~few hundred tokens) that per-meeting embeddings are sufficient and chunking would add complexity without benefit.

### Retrieval & answering

1. Embed the incoming question (same model)
2. Compute cosine similarity against every stored vector for that user (brute-force; sub-millisecond at tens-to-thousands of vectors — no ANN index needed)
3. Filter to `similarity = 0.15`, rank descending, take top 5
4. Fallback: if nothing clears 0.15, use the 5 most recent meetings
5. Build a context block from the retrieved meetings
6. Call OpenRouter chat completion (`temperature: 0.2`) with a system prompt that hard-constraints the model to answer **only from the provided context** — never from its training knowledge
7. Return answer + cited meeting sources

**Lazy backfill:** `ensureIndexed()` embeds any unindexed meeting on the first chat request that encounters it — no migration script needed.

### Key parameters

| Parameter | Value |
|---|---|
| Embedding model | `openai/text-embedding-3-small` (1536 dims) |
| TOP_K | 5 |
| MIN_SIMILARITY | 0.15 |
| Answer temperature | 0.2 |
| Max tokens | 4096 |
| LLM timeout | 120 s (connect: 10 s) |

---

## WhatsApp agent (OpenClaw)

OpenClaw runs a separate LLM process that interfaces with WhatsApp and calls this API's endpoints as tools via shell scripts. The agent's behaviour is defined entirely in `openclaw-workspace/AGENTS.md` — it is not hard-coded control flow.

### Conversational state machine

```
GREET ? ASK_EMAIL ? ASK_PASSWORD ? LOGGED_IN
```

From `LOGGED_IN`:
- Message starts with `ask ...` ? routes to `POST /api/chat` *(available to all plan tiers)*
- Anything else ? treated as a transcript for `POST /api/meetings` *(Pro-gated)*

### Tool scripts

| Script | Wraps | Notes |
|---|---|---|
| `agent-login.{ps1,sh}` | `POST /api/auth/login` | Outputs JWT + `HTTP_STATUS:<code>` |
| `agent-create-meeting.{ps1,sh}` | `POST /api/meetings` | Sends `X-Agent-Channel: whatsapp` header |
| `agent-ask.{ps1,sh}` | `POST /api/chat` | Returns answer + source meetings |

Each script prints the raw response body followed by `HTTP_STATUS:<code>` on the last line. The agent LLM reads the status code to decide its next conversational move (e.g. `401` ? prompt re-login, `403 limitReached` ? prompt upgrade).

---

## Reliability

- **Model fallback chains:** every OpenRouter request includes a primary model + up to 2 fallbacks (OpenRouter enforces a hard cap of 3 total — discovered and handled). If the primary is rate-limited or down, OpenRouter tries the next automatically.
- **HTTP-200 error detection:** OpenRouter sometimes returns `{"error": ...}` with a `200` status. `OpenRouterClient` checks for this explicitly before parsing the response as a success.
- **Credit refund on failure:** if a credit is consumed but summary generation subsequently fails, the credit is refunded atomically.
- **Global exception handler:** `@RestControllerAdvice` in `GlobalExceptionHandler` replaces Spring's default opaque `400 Bad Request` with structured error bodies — necessary for the WhatsApp agent to handle errors programmatically.

---

## Monetization

| Tier | Summary limit | WhatsApp access | Price |
|---|---|---|---|
| Free | 3 lifetime | ? | $0 |
| Credit | 1 per credit | ? | $1 / credit |
| Pro | Unlimited | ? | $49 / month |

Access priority per request: `Pro active` ? unlimited; `credits > 0` ? spend one (atomic); `free cap not reached` ? allow; otherwise `403`.

Stripe Checkout handles payment collection. The webhook handler (`POST /api/webhooks/stripe`) is verified with HMAC (`stripe.webhook-secret`) — not JWT — because it is called by Stripe's servers, not a logged-in user. Subscription state (plan, credits, Stripe customer/subscription IDs) is updated from webhook events so it stays correct even if the user closes the browser before the redirect.

---

## Running locally

### Prerequisites

- JDK 21+ (tested on 25)
- Maven
- MySQL 8
- Node.js 20+ + Angular CLI
- Stripe CLI (for local webhook forwarding)

### Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `OPENROUTER_API_KEY` | Yes | LLM + embedding calls |
| `STRIPE_SECRET_KEY` | Yes | Stripe API |
| `STRIPE_WEBHOOK_SECRET` | Yes | Webhook HMAC verification (`whsec_...`) |
| `STRIPE_PRO_PRICE_ID` | Yes | Pro subscription price ID |
| `STRIPE_CREDITS_PRICE_ID` | Yes | Credit pack price ID |
| `JWT_SECRET` | Prod only | Base64 signing secret |

### Startup order

```powershell
# 1. Ensure MySQL is running and ai_meeting_db exists (created automatically on first run)

# 2. Start backend
cd backend
$env:OPENROUTER_API_KEY = "sk-or-..."
$env:STRIPE_SECRET_KEY  = "sk_test_..."
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"

# 3. Start Stripe listener — paste the printed whsec_... into application-dev.properties
#    if it differs from the current value, then restart the backend
stripe listen --forward-to localhost:8080/api/webhooks/stripe

# 4. Start frontend
cd frontend
npm install
ng serve
# ? http://localhost:4200
```

> **Before each dev session:** check for stale processes from previous sessions.
> ```powershell
> Get-Process | Where-Object { $_.ProcessName -match 'stripe|java' } | Select ProcessName, Id
> ```

---

## Production

| Component | Setup |
|---|---|
| API | Oracle Cloud Always Free Ampere A1 VM, `prod` Spring profile, SQLite on persistent disk |
| Frontend | Netlify, `environment.prod.ts` points to VM IP |
| WhatsApp agent | OpenClaw running on the same VM |
| Secrets | Passed as environment variables — never committed |
