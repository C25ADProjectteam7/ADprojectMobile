# Mobile Project Status Report

## 1. Project Overview

`ADprojectMobile` (Team7) is the Mobile-side backend for "Smart Travel &
Expense Hub" — an AI-assisted business travel planning and expense platform.
Scope, as reflected in the repository:

- **Spring Boot multi-module backend** (`mobile-common`, `mobile-security`,
  `mobile-data`, `mobile-business`, `mobile-api`) — REST API for auth, trips,
  bookings, expenses, and agent chat, backed by MySQL.
- **Python Agent + ML service** (`agent-ml-service`, FastAPI) — an LLM-driven
  trip-planning agent (DeepSeek, tool-calling) plus ML inference (price
  prediction, budget allocation), called by the Spring Boot backend over
  HTTP.
- **Docker Compose** orchestration (MySQL + Spring API + Agent/ML + Nginx).
- **GitHub Actions CI/CD** (build, test, SAST, Docker image scan, deploy).

The repository is currently at an **initial scaffold stage** for almost all
components (class shells with docstrings and `TODO`s, no method bodies) —
the single commit `2b369e5 Initial project scaffold` plus a series of CI
pipeline fixes make up all history on `main`. The one component with real,
working, tested code is the ML Hotel Price Prediction API, built on the
current feature branch.

## 2. Current Repository / Branch Status

- **Repository:** `ADprojectMobile` (local clone; remote `origin` → GitHub, org/team `C25ADProjectteam7`)
- **Active branch:** `feature/ml-hotel-price-api`
- **Remote status:** **LOCAL ONLY / NOT PUSHED** — the ML feature changes described in this report exist only in the local working tree and are not yet available in the remote GitHub repository.
- **Relevant branches:** `main` (only other branch present, local + `origin/main`)
- **Uncommitted changes (working tree, not yet committed):**
  - Modified: `README.md`, `agent-ml-service/main.py`, `agent-ml-service/ml/price_predictor.py`, `agent-ml-service/ml/routes.py`, `agent-ml-service/requirements.txt`
  - Untracked (new): `agent-ml-service/ml/schemas.py`, `agent-ml-service/tests/`, `docs/` (`docs/ml/hotel-price-api-contract.md`, `docs/ml/hotel-price-dataset-requirements.md`), `MOBILE_PROJECT_STATUS_REPORT.md` (this file)
- **Report generated:** 2026-08-01
- **Revised:** 2026-08-04, after an independent read-only code review flagged several accuracy issues (currency handling bug fixed in code; CI/CD, deployment, entity-count, and test-environment claims in this report corrected below — see §9, §13)

## 3. Overall Status

| Area | Status |
|---|---|
| Backend (Java/Spring Boot) | **Not Started** (scaffold only — annotated empty classes) |
| Agent (Python) | **Not Started** (scaffold only — docstrings + TODOs, no logic) |
| Machine Learning — Hotel Price Prediction | **In Progress** (mock API complete and tested; real model **Not Started**) |
| Android Integration | **Unknown** (no Android client code in this repository) |
| Database | **Not Started** (schema is TODO comments only, no DDL) |
| CI/CD Pipeline | **In Progress** (5 stages defined in `.github/workflows/ci.yml` and iterated on per commit history; **not independently verified as currently passing** from repo state alone — see §9 for confirmed gaps) |

## 4. Backend Status

All Java modules exist with the intended package/module structure, but
contain **no implemented logic** — every class is an empty
shell (`@Service`, `@RestController`, `@Entity`, `@Repository` annotations
present) with a Chinese docstring describing the intended design and a
`// TODO` listing what needs to be written. `MobileApplication.java`'s own
TODO notes that `scanBasePackages` is not yet configured to cover all
submodules, so build/component-scan wiring across modules should not be
assumed complete either.

- **mobile-common:** DTOs (`TripRequest`, `LoginRequest`, `BookingDTO`, etc.), constants (`BookingStatus`, `TripStatus`, `UserRole`), exceptions, and utils (`DateTimeUtils`, `MonetaryUtils`, `StringUtils`) — all class shells, no fields/methods implemented. 23 TODO markers.
- **Security / JWT:** `SecurityConfig`, `JwtAuthFilter`, `JwtTokenProvider`, `UserDetailsServiceImpl` — all empty shells (e.g. `JwtTokenProvider` has no `generateToken`/`validateToken` implementation yet). 4 TODO markers.
- **Data / JPA:** 12 entities (`User`, `Trip`, `Booking`, `Expense`, `Hotel`, `Flight`, `Itinerary`, `ItineraryItem`, `Attraction`, `Restaurant`, `PlaceImage`, `AgentConversation`) and 7 repositories. 11 of the 12 entities have `@Id`/`@GeneratedValue` defined with no other fields/relations mapped; `User.java` is further behind than the rest — it has no `@Entity` annotation and no `@Id` at all, just a bare class with a TODO comment. Repositories are bare `JpaRepository` interfaces with no custom queries. 36 TODO markers.
- **Business:** `AuthService`, `TripService`, `BookingService`, `ExpenseService`, `AgentOrchestrator`, `AmadeusClient`, `GooglePlacesClient`, `ReceiptOcrService`, `FileStorageService` — all empty class shells. 9 TODO markers.
- **REST API:** 6 controllers (`AuthController`, `TripController`, `BookingController`, `ExpenseController`, `AgentController`, `UserController`) — routes/endpoints documented in comments but not implemented (no `@GetMapping`/`@PostMapping` methods yet). `GlobalExceptionHandler`, `OpenApiConfig` present but not inspected in depth this pass. 9 TODO markers.
- **Database:** `docker/mysql/init.sql` contains only comment placeholders for 12 planned tables — **no actual `CREATE TABLE` DDL exists**. The schema will not create any tables if run as-is.
- **Tests:** Only `mobile-api/src/test/.../SmokeTest.java` exists — a single trivial test (`assertTrue(true)`) confirming the test harness runs. No real unit/integration tests for any backend logic yet.

## 5. Agent Status

`agent-ml-service/agent/` — also scaffold-only:

- **Implemented:** `deepseek_client.py` instantiates an `AsyncOpenAI` client pointed at DeepSeek's OpenAI-compatible endpoint (reads `config.DEEPSEEK_API_KEY` / `config.DEEPSEEK_BASE_URL`). This is the only non-empty line of logic in the agent package.
- **Placeholder / TODO:** `orchestrator.py` (itinerary generation/modification — all TODO), `tools.py` (tool-calling schemas + implementations — all TODO), `routes.py` (`/api/agent/*` endpoints — all TODO, router exists but has zero routes registered), and `deepseek_client.py`'s actual methods (`chat_completion`, `chat_with_tools`, `get_embedding` — all TODO).
- **External API integration:** Not implemented (Amadeus, Google Places clients live in the Java `mobile-business` module and are also unimplemented shells).
- **LLM integration:** Client is wired to config but no chat/tool-calling method exists yet.
- **Tests:** None for the agent package.
- **Blockers:** `main.py` does not register the agent router at all yet (`# TODO: register agent routes` is still present) — so even if `agent/routes.py` had endpoints, they would not currently be reachable.

## 6. Machine Learning Status

**Hotel Price Prediction API — implemented and tested (mock stage).**

- **Endpoint:** `POST /api/ml/predict-hotel-price`, registered in `main.py` via `app.include_router(ml_router)`.
- **Request/Response schema:** `agent-ml-service/ml/schemas.py` — `HotelPriceRequest` (city, check_in/out/booking dates, star rating, room type, guests, currency) and `HotelPriceResponse` (predicted price per night, predicted total, nights, currency, model_status, model_version, is_mock, message). Full field-level detail in `docs/ml/hotel-price-api-contract.md`.
- **Predictor:** `MockHotelPricePredictor` in `ml/price_predictor.py` — deterministic rule-based (city base price × star multiplier × room-type multiplier), explicitly not a trained model.
- **Mock status:** Every response is explicitly labeled — `is_mock=true`, `model_status="mock"`, `model_version="mock-v0"`, plus a human-readable disclaimer in `message`. This is enforced by contract, not just convention.
- **Currency handling (fixed 2026-08-04):** An independent review found that the original mock echoed back whatever `currency` the caller sent (e.g. `JPY`) while computing off USD-only base prices — a business-logic bug, not just a documentation gap. Fixed: `currency` is now validated and normalized to uppercase, and only `USD` is accepted (422 otherwise) until real FX conversion exists. `city` is now also trimmed and rejected if blank/whitespace-only after trimming.
- **Tests:** `agent-ml-service/tests/test_ml_api.py` — 21 tests (13 original + 8 added 2026-08-04 in response to review feedback: exact formula value, unknown-city fallback, whitespace/trimmed city, unsupported/garbage/lowercase currency, invalid room type), **all passing**. Verified against the ad-hoc `.venv` currently installed on this machine (FastAPI 0.141.1, Pydantic 2.13.4 — newer than the pins in `requirements.txt`). **Not verified** against the exact pinned `requirements.txt` versions or the Docker target (`python:3.11-slim`): this machine only has Python 3.14 available, on which `pydantic-core==2.9.0` (a pin in `requirements.txt`) fails to build from source (PyO3 doesn't support 3.14 yet), and the local Docker daemon was not running this session. This is a real, confirmed gap, not just a caveat — someone should run `pytest` inside the actual Docker image (or on Python 3.11) before relying on "tests pass" as proof for the pinned/deployed environment.
- **API Contract:** `docs/ml/hotel-price-api-contract.md` — endpoint, schema, examples (success + 422), mock-vs-real semantics, and the caller-agnostic HTTP integration note (no Spring Boot/Agent caller hard-coded yet since ownership isn't finalized).
- **Dataset Requirements:** `docs/ml/hotel-price-dataset-requirements.md` — target definition, required/optional features, time-based split rationale, candidate baseline models (Linear Regression, Random Forest, XGBoost), evaluation metrics (MAE/RMSE/MAPE), dataset source selection criteria (license, ToS, PII).
- **Not yet done — real dataset and trained model:** No dataset has been selected, downloaded, or validated. `RealHotelPricePredictor` does not exist. This is the explicit, acknowledged gap between the current mock and a real ML deliverable.
- **Integration dependency:** No caller (Spring Boot `AgentOrchestrator`/`AmadeusClient` or the Python Agent) currently calls this endpoint — both are unimplemented shells (see §4, §5). The contract is written to be caller-agnostic until that's decided.
- **Next steps:** (1) source and validate a dataset against `docs/ml/hotel-price-dataset-requirements.md`; (2) train a baseline model; (3) implement `RealHotelPricePredictor` behind the same `predict()` interface; (4) swap the import in `ml/routes.py` — no contract change needed.

## 7. Android Integration Status

- **Android client in this repo:** No — no Android/Gradle-Android project, no `AndroidManifest.xml`, and no `com.android.*` plugin references were found anywhere in this repository. Android development is out of scope for `ADprojectMobile` or lives in a separate repository.
- **API contract availability for Android:** The Hotel Price Prediction contract (`docs/ml/hotel-price-api-contract.md`) is written in a caller-agnostic way and could be consumed by an Android client via the same HTTP interface Spring Boot/Agent would use, but no Android-specific integration has been done or requested.
- **Items still needing confirmation:** UNKNOWN — whether an Android repo exists elsewhere, and whether Android will call this ML endpoint directly or only through Spring Boot.

## 8. Database Status

- **MySQL config:** `docker-compose.yml` defines a MySQL 8.0 service with healthcheck, persisted volume, and env-var-driven credentials (defaults present in compose file for local dev, overridable via `.env`).
- **Schema/entities:** JPA entities exist in `mobile-data` but are field-incomplete (only `@Id` defined on each). `docker/mysql/init.sql` is **12 tables of comments only — no DDL**.
- **DDL/migrations:** None exist yet. No migration tool (Flyway/Liquibase) is configured.
- **Can it run today:** The MySQL container will start, but `mobile` database will have **no tables** — `init.sql` has no executable `CREATE TABLE` statements. Spring Boot would fail against this schema once entity mapping is implemented.
- **Credentials risk:** `docker-compose.yml` has hardcoded fallback defaults (`Root@2026!`, `Mobile@2026!`) used when `MYSQL_ROOT_PASSWORD`/`MYSQL_PASSWORD` env vars are unset. These are committed to the repo as fallback values — low risk for local dev but should not be relied on for any shared/deployed environment; `.env.example` documents the intended override path and `.env` itself is correctly git-ignored.
- **Confirmed backend risk (not fixed here — outside ML scope, flagging for the Backend owner):** `mobile-api/src/main/resources/application.properties` has two lines with trailing `# comment` text after the value on the same line (`spring.jpa.hibernate.ddl-auto=validate          # prod: validate; dev override: update` and `app.jwt.expiration-ms=86400000                   # 24 hours`). Standard Java `.properties` syntax does **not** support inline/trailing comments — `#` only starts a comment at the beginning of a line — so both trailing comments would be parsed as literal part of the value. This would very likely break Spring Boot startup: `ddl-auto` would receive an invalid enum string, and `expiration-ms` would fail to bind as a `long`. This was verified by reading the file directly, not just reported secondhand.
- **Unknowns:** Whether these compose-file default passwords are also used in the actual Digital Ocean deployment (would need to be confirmed with DevOps).

## 9. CI/CD and Testing Status

`.github/workflows/ci.yml` defines 5 stages, triggered on push/PR to `main`/`develop`. This section was corrected 2026-08-04 after an independent review — the original version of this report overstated the pipeline as "functioning" based only on commit-message evidence, without checking actual Actions run results (not accessible from local repo state) or reading the workflow/build files closely enough to catch the gaps below.

1. **Build** — Gradle build (`gradle build -x test`), uploads JAR artifact. Configured.
2. **Test** — `gradle test`, JUnit reporter. Configured — but only `SmokeTest` (Java) exists to run. **The Python ML test suite (21 tests in `agent-ml-service/tests/`) is not part of this workflow at all** — there is no Python setup, `pip install`, or `pytest` step anywhere in `ci.yml`. This is a real, confirmed gap: the ML work in this report has no CI coverage today.
3. **SAST** — SpotBugs + OWASP Dependency Check. Configured, but SpotBugs is set to `ignoreFailures = true` in `build.gradle:38` (comment: "tighten when real code is in place") — so it currently reports findings without failing the build; it is not yet a hard gate.
4. **Docker Build** — builds image via `Dockerfile.ci` + pre-built JAR, Trivy CVE scan (HIGH/CRITICAL, fails build on hit), uploads SARIF to CodeQL. Configured, gated to `main`/`develop`.
5. **Deploy** — SSH deploy to Digital Ocean (`git pull` + `docker compose up -d --build`), gated to `main` only. Configured. The deploy script has a hardcoded path (`cd /home/xiazihang/ADprojectMobile`) tied to what looks like a personal account rather than a dedicated deploy user — worth confirming with whoever owns deployment.

Recent commit history (`c0db5fd`, `2f7e42f`, `6e7a538`, `51849bb`, `c55f1f6`, and earlier) shows active, iterative debugging of this pipeline, which is evidence of real engineering effort — but is not the same as confirming the pipeline is currently green. **No GitHub Actions run output was inspected in this session** (not accessible from local repo state); "configured" and "currently passing" are being kept as separate claims in this report going forward.

## 10. Implemented This Period

- `agent-ml-service/ml/schemas.py` — `HotelPriceRequest`/`HotelPriceResponse` Pydantic v2 models with cross-field date validation
- `agent-ml-service/ml/price_predictor.py` — `MockHotelPricePredictor`
- `agent-ml-service/ml/routes.py` — `POST /api/ml/predict-hotel-price`
- `agent-ml-service/main.py` — router registration, fixed stale docstring endpoint name
- `agent-ml-service/tests/test_ml_api.py` — 21 tests, all passing (13 original + 8 added 2026-08-04)
- `agent-ml-service/requirements.txt` — added `pytest` for testing (an earlier working-tree edit briefly duplicated `httpx==0.27.2`; the final diff nets out to just the `pytest` addition, no duplicate present)
- `docs/ml/hotel-price-api-contract.md` — full API contract; corrected 2026-08-04 (currency section, Docker Compose hostname `agent-ml` vs. the doc's earlier incorrect `agent-ml-service`, replacement-path wording)
- `docs/ml/hotel-price-dataset-requirements.md` — dataset research note
- `README.md` — new "Machine Learning — Hotel Price Prediction" section; currency restriction note added 2026-08-04
- **2026-08-04 fix:** `agent-ml-service/ml/schemas.py` — currency is now validated/normalized to `USD` only (was previously echoing back any 3-character string, misrepresenting USD-based mock prices as other currencies); `city` is now trimmed and rejected if blank after trimming
- This report

## 11. In Progress

- Hotel Price Prediction ML capability overall (mock stage done; real model pending dataset)

## 12. Not Implemented / Planned

- All Spring Boot backend logic (auth, trips, bookings, expenses, security, JPA mappings, DB schema DDL)
- Agent orchestration, tool-calling, DeepSeek chat/embedding methods, `/api/agent/*` routes (including router registration in `main.py`)
- `/api/ml/allocate-budget` (budget allocation ML endpoint — explicitly out of scope for today per this session's instructions)
- `GET /api/ml/model-info` (model metadata endpoint)
- Real trained `RealHotelPricePredictor` and its dataset
- Any Android client work (not in this repo)

## 13. Blockers and Risks

- **No real dataset for hotel price prediction** — blocks any progress beyond the mock predictor; requires deliberate sourcing/vetting per `docs/ml/hotel-price-dataset-requirements.md`.
- **DB schema is not executable** — `init.sql` has no DDL; any backend work that depends on persistence will fail against a fresh container until entities and DDL are written together.
- **Agent router not registered** — even once `agent/routes.py` has real endpoints, `main.py` needs the same `include_router` treatment applied to `ml_router` today.
- **No caller for the ML endpoint yet** — integration ownership between Spring Boot vs. the Python Agent for calling `/api/ml/predict-hotel-price` has not been decided; contract is written to stay neutral until it is.
- **Confirmed (2026-08-04): `application.properties` likely breaks Spring Boot startup** — two trailing `#` comments on the same line as a value (`ddl-auto`, `jwt.expiration-ms`) will be parsed as part of the value under standard Java `.properties` syntax. Flagged for the Backend owner; not fixed here since it's outside ML scope. See §8.
- **Confirmed (2026-08-04): Python ML tests have zero CI coverage** — `ci.yml` has no step that installs or runs anything in `agent-ml-service/`. See §9.
- **Confirmed (2026-08-04): pinned `requirements.txt` not verified locally** — could not be installed on this machine (Python 3.14 only; `pydantic-core==2.9.0` has no 3.14 wheel and fails to build via PyO3), and the local Docker daemon was not running to test against the actual `python:3.11-slim` target. The 21 passing tests are evidence against the ad-hoc newer-version `.venv` only. See §6.

## 14. Integration Dependencies

- **Spring Boot ↔ Agent/ML:** Not yet wired — `AgentOrchestrator` (Java) and `agent/routes.py` (Python) are both empty; `ml_router` is registered and independently callable today via direct HTTP.
- **API caller ownership:** Undecided — whether Spring Boot, the Python Agent, or both call `/api/ml/predict-hotel-price` directly.
- **Database:** Backend cannot persist anything meaningfully until entities + DDL are both completed together.
- **Android:** No known dependency in this repo; UNKNOWN whether a separate Android repo depends on this API contract.
- **Web / Manager side:** UNKNOWN — no information in this repository about a Web/Manager-side project to cross-reference.
- **DevSecOps:** CI/CD pipeline (§9) is the shared surface — this report is meant to merge with a DevSecOps-side report per this session's instructions.

## 15. Next Actions

Deadlines below are targets reported by the ML contributor for their own piece, not confirmed team commitments — marked `TO CONFIRM` where this report cannot verify them from repo state.

| Priority | Action | Owner | Deadline | Dependency | Definition of Done |
|---|---|---|---|---|---|
| High | Source and vet a real hotel-price dataset against `docs/ml/hotel-price-dataset-requirements.md` | TBD (ML) | TO CONFIRM (informally targeted ~2026-08-05) | None | Dataset selected, license confirmed, required features present, documented in the research note |
| High | Train baseline model(s) (Linear Regression → Random Forest → XGBoost) and evaluate (MAE/RMSE/MAPE) | TBD (ML) | TO CONFIRM (informally targeted ~2026-08-05) | Dataset sourced | At least one baseline trained and evaluated against a time-based split |
| Medium | Implement `RealHotelPricePredictor` behind existing `predict()` interface, swap into `ml/routes.py` | TBD (ML) | After baseline model exists | Trained model artifact | `model_status` changes to `"baseline"`/`"trained"`, existing 21 tests + new model-specific tests pass |
| Medium | Decide and implement Spring Boot ↔ ML integration (who calls `/api/ml/predict-hotel-price`) | TBD (Backend + ML) | TO CONFIRM (next team meeting, informally ~2026-08-06) | API contract (done) | `AgentOrchestrator` or equivalent successfully calls the endpoint over HTTP in a dev environment |
| Medium | Add Python ML test suite to `ci.yml` (currently zero CI coverage) | TBD (DevSecOps/Backend) | TO CONFIRM | Ownership of `ci.yml` | `pytest` step runs `agent-ml-service/tests/` in CI and reports results |
| Medium | Fix `application.properties` trailing-comment lines (`ddl-auto`, `jwt.expiration-ms`) before relying on Spring Boot startup | TBD (Backend) | TO CONFIRM | None | App starts cleanly with `spring.profiles.active=dev` and correct property values bound |
| Medium | Write real DB DDL (`init.sql`) alongside completed JPA entities | TBD (Backend) | TO CONFIRM | Entity field definitions | `docker compose up -d mysql` produces all 12 tables with correct relations |
| Low | Register agent router in `main.py` once `agent/routes.py` has real endpoints | TBD (Agent) | TO CONFIRM | Agent implementation | `/api/agent/*` reachable and in OpenAPI schema |

## 16. Teams-Ready Summary

**English (150–250 words):**

The Mobile repository (`ADprojectMobile`) remains at an initial-scaffold stage for the Spring Boot backend, security/JWT layer, JPA entities, and the Python Agent — all exist as annotated class shells with documented intent but no implemented logic, and the database schema file currently has no executable DDL. The one area with concrete, tested progress this period is Machine Learning: a Hotel Price Prediction API (`POST /api/ml/predict-hotel-price`) is fully implemented behind a documented, caller-agnostic contract, backed by Pydantic request/response validation and a deterministic mock predictor that is explicitly and unambiguously flagged as non-ML (`is_mock=true`) in every response. An independent review caught a real bug (the mock echoed back any currency code while pricing off USD-only base values) which has been fixed — the API now accepts USD only until real FX conversion exists. 21 automated tests pass, covering validation, exact-value correctness, determinism, and OpenAPI schema presence — verified against the locally installed environment, not yet against the exact pinned `requirements.txt` versions or the Docker target. Supporting documentation was added: a full API contract (`docs/ml/hotel-price-api-contract.md`) and a dataset/research requirements note (`docs/ml/hotel-price-dataset-requirements.md`) defining what a real training dataset must satisfy. No real dataset has been sourced yet — this is the primary blocker to moving from mock to trained predictions, along with undecided integration ownership between the Spring Boot backend and the Python Agent for calling this endpoint. CI/CD is configured across 5 stages but not confirmed currently passing from repo state alone, and notably does not yet run the Python ML tests at all. Next steps center on dataset sourcing, baseline model training, adding ML tests to CI, and deciding the Spring Boot/Agent integration path.

**中文简版：**

整个 Mobile 仓库（Spring Boot 后端、安全/JWT 层、JPA 实体、Python Agent）目前仍处于初始脚手架阶段——都是带注解和设计说明的空类，没有真正实现的逻辑，数据库 DDL 也还没写。本阶段唯一有实质性、可验证进展的是机器学习部分：酒店价格预测 API（`POST /api/ml/predict-hotel-price`）已经完整实现，有明确的、不绑定调用方的 API Contract，Pydantic 请求/响应校验齐全，Mock predictor 是确定性规则实现，且每次响应都明确标记为非真实 ML 结果（`is_mock=true`）。经独立 review 发现一个真实 bug——之前会把任意 currency 原样返回，但计算基础其实只有 USD——现已修复，mock 阶段现在只接受 USD。21 个自动化测试全部通过，覆盖校验逻辑、精确数值、结果确定性和 OpenAPI schema，但目前只在本机已有环境下验证过，尚未在 `requirements.txt` 锁定的版本或 Docker 目标环境下验证。同时补充了 API Contract 文档和数据集需求/调研文档，明确了未来真实训练数据集需要满足的条件。目前最大的阻塞点是还没有找到真实可用的数据集，其次是 Spring Boot 后端和 Python Agent 之间尚未确定由谁调用这个 ML 接口。CI/CD 流水线配置了 5 个阶段，但本地仓库状态无法确认当前是否为绿色，且目前完全没有跑 Python ML 测试。下一步重点是寻找数据集、训练 baseline 模型、把 ML 测试接入 CI，并确定后端/Agent 的集成方式。
