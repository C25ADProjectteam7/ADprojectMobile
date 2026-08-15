# Friday Meeting & Learning Pack — Hotel Price Prediction ML API

Purpose: read this in 60–90 minutes and be able to (1) explain the real
implementation accurately, (2) never call the mock a real ML model, (3)
handle likely questions, (4) state next steps. Everything below is based on
the actual code in `agent-ml-service/` as of this session — nothing here is
aspirational or copied from an outline.

---

# Part 1 — Current Status in One Page

## Completed
- `POST /api/ml/predict-hotel-price` — implemented, running, registered in `main.py`.
- Request/response validated with Pydantic v2 (`ml/schemas.py`).
- Deterministic mock predictor (`ml/price_predictor.py`).
- 21 automated tests, all passing (`tests/test_ml_api.py`).
- Currency locked to `USD` only, with case normalization (`usd`→`USD`) and rejection of anything else (422).
- `city` is trimmed; blank/whitespace-only city is rejected (422).
- API Contract doc: `docs/ml/hotel-price-api-contract.md`.
- Dataset Requirements / Research Note: `docs/ml/hotel-price-dataset-requirements.md`.
- `GET /health` — implemented (pre-existing, not part of this work).

## Mock (do not present as real ML)
- `MockHotelPricePredictor` is **deterministic rule-based lookup**: `city base price × star multiplier × room-type multiplier`. No training, no model file, no learned parameters.
- Every response carries `is_mock=true`, `model_status="mock"`, `model_version="mock-v0"`, and a plain-language disclaimer in `message`.
- Numbers are illustrative only — they came from hand-picked base prices in `_CITY_BASE_PRICES`, not from any data source.

## Not Implemented
- No real dataset selected, downloaded, or validated.
- No trained model (`RealHotelPricePredictor` doesn't exist).
- `POST /api/ml/allocate-budget`, `GET /api/ml/model-info` — TODO only in `ml/routes.py`.
- Agent package (`agent-ml-service/agent/`) — scaffold only, not wired into `main.py`.
- Entire Spring Boot backend, DB schema DDL — scaffold only (not part of this deliverable, but relevant if someone asks about the wider project).

## Unknown / Team Decision Needed
- Who calls this endpoint — Spring Boot's `AgentOrchestrator`, the Python Agent, or both? Not decided; contract is written caller-agnostic on purpose.
- Whether a real dataset can be sourced/licensed in time for training.
- Whether the Python ML test suite will be added to CI (currently it is not — see Risks).

## Current Risks
- **CI has zero coverage of the Python ML tests.** `.github/workflows/ci.yml` has no Python/pytest step at all. The 21 passing tests are only known to pass locally.
- **Tests were verified against an ad-hoc local `.venv`, not the pinned `requirements.txt` versions.** Attempting the actual pinned versions on this machine failed outright (`pydantic-core==2.9.0` has no Python 3.14 wheel and fails building from source). Docker wasn't available locally to check the real target (`python:3.11-slim`) either.
- **No dataset sourced yet** — this is the hard blocker between "mock" and "real model."
- Nothing has been committed or pushed — everything described here is still in the working tree.

---

# Part 2 — Core Data Flow

```
HTTP POST /api/ml/predict-hotel-price   (JSON body)
        │
        ▼
FastAPI app                              agent-ml-service/main.py
  app = FastAPI(...)
  app.include_router(ml_router)
        │
        ▼
Router                                   agent-ml-service/ml/routes.py
  router = APIRouter(prefix="/api/ml")
  predict_hotel_price(request: HotelPriceRequest)
        │  FastAPI parses JSON → tries to build a HotelPriceRequest
        ▼
Pydantic validation                      agent-ml-service/ml/schemas.py
  HotelPriceRequest
    - field_validator: strip_and_validate_city
    - field_validator: normalize_and_validate_currency
    - model_validator: validate_dates (check_out > check_in, booking_date <= check_in)
  → if any validator raises ValueError: FastAPI returns 422, request never
    reaches the route body or the predictor
        │  (only reaches here if validation passed)
        ▼
Route body calls the predictor           agent-ml-service/ml/routes.py
  _predictor = MockHotelPricePredictor()   (module-level singleton)
  return _predictor.predict(request)
        │
        ▼
MockHotelPricePredictor.predict()        agent-ml-service/ml/price_predictor.py
  base = _CITY_BASE_PRICES.get(city.lower(), _DEFAULT_BASE_PRICE)
  price_per_night = base * star_mult * room_mult
  total = price_per_night * nights
  → builds and returns a HotelPriceResponse
        │
        ▼
Response model                           agent-ml-service/ml/schemas.py
  HotelPriceResponse (response_model= on the route)
  → FastAPI serializes this Pydantic model to JSON
        │
        ▼
JSON Response                            (200 OK, or 422 if validation failed earlier)
```

Files involved, in call order: `main.py` → `ml/routes.py` → `ml/schemas.py` → `ml/routes.py` (calls predictor) → `ml/price_predictor.py` → `ml/schemas.py` (response) → back out through `ml/routes.py`.

---

# Part 3 — Five Core Files

### `main.py`
- **Responsibility:** FastAPI app entry point; wires the ML router in; defines `/health`.
- **Important class/function:** `app = FastAPI(...)`; `health_check()`; `app.include_router(ml_router)`.
- **Input:** none directly (delegates routing).
- **Output:** the running ASGI app; `/health` returns `{"status": "healthy", "service": "agent-ml-service"}`.
- **Called by:** `uvicorn main:app` (the process entry point); Docker/Spring Boot healthcheck hits `/health`.
- **Depends on:** `ml/routes.py` (imports and registers `ml_router`).
- **Common failure:** if `ml/routes.py` or anything it imports throws at import time, the whole app fails to start — there's no per-router isolation.
- **One sentence to remember:** *This file is just wiring — the docstring at the top now explicitly marks which endpoints are `[implemented]` vs `[planned]`, so it can't be misread as a feature list of what exists.*

### `ml/schemas.py`
- **Responsibility:** defines and validates the request/response shape — this is the API contract enforced in code.
- **Important class/function:** `HotelPriceRequest` (with `strip_and_validate_city`, `normalize_and_validate_currency`, `validate_dates`); `HotelPriceResponse`; `SUPPORTED_CURRENCIES = frozenset({"USD"})`.
- **Input:** raw JSON body from the HTTP request.
- **Output:** a validated `HotelPriceRequest` object, or a `ValueError` that FastAPI turns into a 422.
- **Called by:** FastAPI itself, automatically, before the route function body runs (via the type hint on `predict_hotel_price`).
- **Depends on:** nothing else in this project (leaf module, only depends on `pydantic`/`datetime`).
- **Common failure:** a currency other than `USD` (case-insensitive) or a blank/whitespace-only city both raise `ValueError` → 422. `check_out_date <= check_in_date` or `booking_date > check_in_date` also raise → 422.
- **One sentence to remember:** *This file is why the app is safe to demo — bad input never reaches the predictor.*

### `ml/routes.py`
- **Responsibility:** declares the HTTP endpoint and connects validated requests to the predictor.
- **Important class/function:** `router = APIRouter(prefix="/api/ml", ...)`; `_predictor = MockHotelPricePredictor()` (created once at import time, reused for every request); `predict_hotel_price(request)`.
- **Input:** an already-validated `HotelPriceRequest` (Pydantic guarantees this by the time the function body runs).
- **Output:** a `HotelPriceResponse` (declared via `response_model=HotelPriceResponse`, so FastAPI also validates the outgoing shape).
- **Called by:** `main.py` (`app.include_router(ml_router)`); ultimately by whatever HTTP client calls the API (Spring Boot, Agent, `curl`, tests).
- **Depends on:** `ml/schemas.py` (types), `ml/price_predictor.py` (`MockHotelPricePredictor`).
- **Common failure:** none expected here — all failure modes were pushed earlier into schema validation on purpose. If this function ever throws, it's an unhandled 500, which would be a new bug (none observed).
- **One sentence to remember:** *This file is one line of real logic (`return _predictor.predict(request)`) — swapping to a real model only touches this file's import and one line.*

### `ml/price_predictor.py`
- **Responsibility:** the actual prediction logic — currently a deterministic formula, not a trained model.
- **Important class/function:** `MockHotelPricePredictor.predict()`; constants `_CITY_BASE_PRICES`, `_DEFAULT_BASE_PRICE`, `_STAR_MULTIPLIERS`, `_ROOM_MULTIPLIERS`.
- **Input:** a validated `HotelPriceRequest`.
- **Output:** a `HotelPriceResponse` with `predicted_price_per_night`, `predicted_total_price`, `number_of_nights`, and mock metadata (`is_mock=True`, `model_status="mock"`, `model_version="mock-v0"`, disclaimer `message`).
- **Called by:** `ml/routes.py`'s `predict_hotel_price()`.
- **Depends on:** `ml/schemas.py` (types only — no dependency on any ML library; `scikit-learn`/`xgboost`/`pandas` in `requirements.txt` are unused by this file today).
- **Common failure:** an unrecognized city does **not** fail — it silently falls back to `_DEFAULT_BASE_PRICE` (120.0). This is by design (documented in tests), but worth remembering: the mock never rejects a city, it just guesses.
- **One sentence to remember:** *Formula: `price_per_night = base(city) × star_multiplier × room_multiplier` — for Tokyo, 4-star, double: `160.0 × 1.4 × 1.0 = 224.0`.*

### `tests/test_ml_api.py`
- **Responsibility:** proves the endpoint behaves as documented — both happy path and validation edge cases.
- **Important class/function:** `client = TestClient(app)` (drives the real FastAPI app in-process, no network); `VALID_REQUEST` (the one canonical valid payload every test starts from).
- **Input:** HTTP calls via `TestClient`, built from `VALID_REQUEST` with targeted overrides (`{**VALID_REQUEST, "field": bad_value}`).
- **Output:** pass/fail assertions on status code and response body.
- **Called by:** `pytest` (developer/CI, though **not currently wired into CI** — see Risks).
- **Depends on:** `main.py` (imports the real `app`), transitively everything else.
- **Common failure:** none currently — 21/21 pass. Historically (before this session's fix round), the tests never asserted an exact known price value, so a broken formula could have silently passed; `test_known_price_formula_produces_exact_value` now closes that gap.
- **One sentence to remember:** *These tests prove the mock is internally consistent, well-validated, and produces exactly the documented formula — they prove nothing about real-world hotel prices.*

---

# Part 4 — Exact Runbook

```bash
# 1. Enter the service directory
cd agent-ml-service

# 2. Install dependencies (creates/uses your existing .venv if you have one)
pip install -r requirements.txt
# Expected: package install output, ending without errors.
# Known issue: on Python 3.14, scikit-learn / pydantic-core in this pinned
# file may fail to build from source. If that happens, this is a known,
# already-flagged environment gap (see Part 1 Risks) — not something you broke.

# 3. Start FastAPI
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
# Expected: "Uvicorn running on http://0.0.0.0:8000" and no traceback.

# 4. Open the health endpoint (new terminal)
curl http://localhost:8000/health
# Expected: {"status":"healthy","service":"agent-ml-service"}

# 5. Call the prediction endpoint
curl -X POST http://localhost:8000/api/ml/predict-hotel-price \
  -H "Content-Type: application/json" \
  -d '{
    "city": "Tokyo",
    "check_in_date": "2026-08-10",
    "check_out_date": "2026-08-13",
    "booking_date": "2026-07-31",
    "hotel_star_rating": 4,
    "room_type": "double",
    "number_of_guests": 2,
    "currency": "USD"
  }'
# Expected: 200 OK, JSON with predicted_price_per_night=224.0,
# predicted_total_price=672.0, is_mock=true, model_status="mock".

# 6. Run the tests
pytest tests/ -v
# Expected: "21 passed" (plus one unrelated StarletteDeprecationWarning).

# 7. Check OpenAPI
curl http://localhost:8000/openapi.json | python3 -m json.tool | grep predict-hotel-price
# Expected: the path "/api/ml/predict-hotel-price" is present.
# (Or open http://localhost:8000/docs in a browser for the interactive Swagger UI.)

# 8. Stop the service
# In the terminal running uvicorn: Ctrl+C
# Expected: "Shutting down" then the process exits.
```

---

# Part 5 — Meeting Speaking Pack

**1. 30-second responsibility introduction**
> "I own the Machine Learning piece of the platform — specifically hotel price prediction. My job this sprint was to get a clean, testable API interface in place, with an explicit mock predictor standing in until we have real training data."

**2. 60-second implementation update**
> "There's a working endpoint, `POST /api/ml/predict-hotel-price`, built in FastAPI. It takes city, dates, star rating, room type, guests, and currency, validates all of it with Pydantic — including rejecting bad dates, blank cities, and any currency other than USD — and returns a predicted price per night and total. Right now the prediction itself is a deterministic formula: city base price times a star multiplier times a room-type multiplier, not a trained model. Every response is explicitly labeled `is_mock: true` so nobody downstream can mistake it for a real prediction. I've got 21 automated tests covering the happy path, the exact expected numbers, and every validation edge case, plus a written API contract and a dataset requirements document for the next phase."

**3. 30-second limitation explanation**
> "The biggest limitation is honesty, not capability — this is not ML yet. There's no dataset, no training, no learned parameters. It's a lookup table dressed up as an API so the rest of the team can integrate against a stable contract now, instead of waiting for a trained model."

**4. 30-second next-step proposal**
> "Next step is sourcing a real dataset against the requirements doc I wrote — city/date/star/room coverage, licensing, no leakage — then training a baseline, probably starting with a simple regression before XGBoost. Once that exists, swapping it in is a small, contained change — one import and one instantiation line — because I deliberately isolated the predictor from the route."

**5. "为什么现在只是 mock?"**
> "Because there's no real dataset yet, and I'd rather ship an honestly-labeled mock with a stable contract than block the whole team on data sourcing. Every response says `is_mock: true` so it can never be mistaken for real."

**6. "什么时候训练真实模型?"**
> "Not scheduled yet — it depends on finding and validating a dataset first, which is the actual blocker, not the modeling step itself."

**7. "Dataset 从哪里来?"**
> "Not sourced yet. I wrote down the exact requirements — required fields, city/date coverage, licensing and ToS constraints, no-PII — in `docs/ml/hotel-price-dataset-requirements.md`, so whoever sources it (me or someone else) knows exactly what to check before we commit to a dataset."

**8. "Spring Boot 或 Agent 如何调用?"**
> "Not decided yet, and deliberately so — the contract doc doesn't hard-code a caller. It's a plain HTTP POST, so either Spring Boot's `AgentOrchestrator` or the Python Agent can call it once one of them is actually implemented — right now both are still empty scaffolding, so nothing calls it yet."

**9. "13 tests 证明什么?"** *(note: it's now 21, not 13 — say so)*
> "It's actually 21 now — I added 8 more after a review caught that the original set never checked an exact expected number. They prove the formula is deterministic and matches its documented spec, and that bad input is rejected correctly. They prove nothing about whether the prices are realistic — that's what a real dataset and evaluation would tell us."

**10. 当我不知道答案时的 professional response**
> "I don't know that off the top of my head — let me check the code/docs and follow up rather than guess." (Then actually follow up — don't improvise a number or a status.)

---

# Part 6 — Likely Questions

| # | Question | What I must mention | Common wrong answer |
|---|---|---|---|
| 1 | "Is this a real ML model?" | No — deterministic rule-based mock, `is_mock=true` in every response | Implying it's "basically" ML or "close to" trained |
| 2 | "What's the accuracy?" | Not applicable — there's no model to have accuracy; only a fixed formula | Quoting a made-up accuracy number |
| 3 | "Why USD only?" | Base prices are USD, no FX conversion exists yet; other currencies would be silently wrong if allowed | "We'll add it later" without saying it's currently *rejected*, not just unsupported |
| 4 | "What happens with an unknown city?" | Falls back to a default base price (120.0), does not error | Saying it errors/rejects unknown cities |
| 5 | "Who's calling this API right now?" | Nobody — Spring Boot's orchestrator and the Agent are both still empty scaffolding | Claiming Spring Boot or the Agent already integrates with it |
| 6 | "Is this tested in CI?" | No — confirmed gap, `ci.yml` has no Python/pytest step at all | Saying "yes, it runs in CI" |
| 7 | "Did you verify it against the pinned requirements.txt / Docker environment?" | No — tried, pinned `pydantic-core` fails to build on this machine's Python 3.14; Docker wasn't running locally either | Claiming the pinned/deployed environment was verified |
| 8 | "What's the dataset?" | None yet — only requirements are documented | Naming a specific dataset that hasn't actually been vetted/downloaded |
| 9 | "When will this be 'real'?" | Depends on dataset sourcing first — no firm date | Committing to a specific date without caveats |
| 10 | "Can Android call this directly?" | No Android client exists in this repo; unknown if one exists elsewhere | Assuming yes/no without checking |
| 11 | "What about budget allocation?" | Explicitly out of scope this sprint — `TODO` only in `ml/routes.py` | Implying it's in progress |
| 12 | "Is the mock predictor doing anything with `number_of_guests` or `booking_date`?" | No — both are validated/accepted but not used in the price formula | Claiming they influence the price |
| 13 | "What breaks if I send `check_out_date == check_in_date`?" | 422 — must be strictly after, same-day stays are rejected | Saying same-day is allowed |
| 14 | "Is the API contract locked / final?" | It's a documented target, not a guarantee — real model may need currency/preprocessing changes | Saying nothing will ever change |
| 15 | "Did you find any bugs during this work?" | Yes — a real currency bug (mock echoed back any currency while pricing in USD only); fixed and now covered by tests | Saying everything was clean from the start |

---

# Part 7 — Friday 90-Minute Study Plan

- **0–15 min** — Read Part 1 (Status) and Part 2 (Data Flow) here. Open `main.py`, `ml/routes.py`, `ml/schemas.py`, `ml/price_predictor.py` side by side and trace one request through them by eye.
- **15–30 min** — Run the Part 4 Runbook end to end yourself: start the server, hit `/health`, hit `/predict-hotel-price` with the example payload, confirm you get `224.0` / `672.0`.
- **30–45 min** — Run `pytest tests/ -v`, read through `tests/test_ml_api.py` top to bottom, and for at least 3 tests explain out loud *why* that specific input should produce that specific result.
- **45–60 min** — Read `docs/ml/hotel-price-api-contract.md` and `docs/ml/hotel-price-dataset-requirements.md` fully once each.
- **60–75 min** — Read Part 5 (Speaking Pack) out loud, twice. Time yourself on the 30/60-second segments.
- **75–90 min** — Read Part 6 (Likely Questions) and, without looking at the "what I must mention" column, try to answer each one from memory; check yourself after.

**Definition of Done** (all must be true before the meeting):
- [ ] Can explain the full data flow (Part 2) without looking at notes
- [ ] Can explain what each of the five core files does (Part 3)
- [ ] Can explain the mock's limitations without hedging or overselling
- [ ] Personally ran `pytest tests/ -v` and saw it pass
- [ ] Can deliver the 60-second status update (Part 5.2) from memory
- [ ] Knows the next phase (dataset sourcing → baseline model → swap predictor)

---

# Part 8 — Push Checklist

Only push once **every** item below is true:

- [ ] Codex Critical issues resolved — currency bug fixed and tested; status-report overclaims corrected; test-environment claims stated honestly (all done as of the 2026-08-04 fix round)
- [ ] Tests pass — `pytest tests/ -v` shows 21 passed, run by you personally, not just reported by Claude/Codex
- [ ] Documentation matches code — API contract, README ML section, and this pack all reference the real endpoint path, real formula, real test count (21)
- [ ] No secrets — re-run `grep -rniE "api[_-]?key\s*=|secret\s*=|password\s*=|token\s*=" agent-ml-service/main.py agent-ml-service/ml/ agent-ml-service/tests/ docs/ml/ README.md MOBILE_PROJECT_STATUS_REPORT.md` and confirm no output
- [ ] You personally understand the core data flow (Part 2) — not just "Claude explained it once"
- [ ] You personally ran the tests and watched them pass — not relying on a prior session's report
- [ ] You reviewed `git diff` yourself for every changed file, not just the summaries given to you
- [ ] Team branch workflow confirmed — who reviews the PR, whether Codex or a teammate needs to re-check, and whether `main`/`develop` is the right target branch, confirmed with the team (not assumed)
