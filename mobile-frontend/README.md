# TravelHub Android Frontend - Backend Integration Guide

本文档面向 Team 7 后端成员，用于理解 Android 前端当前结构、认证接口约定、运行配置和合并测试步骤。

## 1. 当前范围

这是 NUS-ISS AD Project 的课程 MVP，不是企业级生产应用。Android 端面向 Traveler / Employee，使用 Kotlin、XML Layout、ViewBinding、Activity 和 Fragment。

当前完成情况：

| 功能 | 状态 | 数据来源 |
|---|---|---|
| Username Login | 已接入真实接口 | `POST /api/auth/login` |
| Register | 已接入真实接口 | `POST /api/auth/register` |
| JWT Session / Bearer Token | 已实现 | Login response |
| Logout | 已实现 | Android 本地 Session 清除 |
| Trip CRUD / List / Detail | 已接入真实接口 | 7 个 `/api/trips` 路径 |
| Trip Agent lifecycle | 已接入启动、任务保存、轮询和详情刷新 | `POST /api/trips/{id}/agent-chat` + `GET /api/agent/tasks/{taskId}` |
| Itinerary Review | 已显示真实持久化 Agent itinerary | `GET /api/trips/{id}/detail`；不再以 Mock itinerary 冒充成功结果 |
| Booking | 已接入创建、列表、详情和取消 | 4 个 `/api/bookings` 路径；始终明确标注为课程 Mock Booking |
| Expense / Claim | 已接入上传、列表、详情和 Finance 状态 | 4 个 /api/expenses 路径；Claim 直接映射 Expense status |
| User Profile | 已接入读取和更新 | GET/PUT /api/users/me |
| ML hotel price | 已接入酒店卡片预测 | POST /api/ml/predict-hotel-price；明确显示 isMock |
| Personalized Recommendations | 未伪造接口或结果 | 当前 Swagger/后端无此端点，保留为 P1 边界 |
| Forgot / Reset Password | Android 页面、验证、Repository 和接口调用已完成 | 匹配的后端实现及真实环境联调待完成 |

Android 目前共声明 26 个 API 方法（Auth 3、Trip 7、Agent 4、Booking 4、Expense 4、User 3、ML 1）。Traveler 主流程所需的 Auth、Trip、Agent task lifecycle、Booking、Expense、User Profile 和 ML 页面均已有 Repository/真实请求流程。Agent 的三个直接生成接口不用于 Trip 持久化流程；User password endpoint 由 Reset Password 使用。

## 2. 项目信息

| 项目项 | 当前值 |
|---|---|
| Git branch | `feature/mobile-frontend` |
| Android application ID | `iss.nus.edu.sg.viewbinding.caproject` |
| Language | Kotlin |
| UI | XML + ViewBinding |
| Minimum SDK | 30 |
| Target SDK | 36 |
| Compile SDK | 36.1 |
| Default API Base URL | `http://10.0.2.2:8080/` |
| Auth API contract | `Swagger_API_Docs.pdf` + repository backend source |

## 3. Authentication Flow

```text
LoginActivity
  -> POST /api/auth/login
  -> AuthRepository
  -> save LoginResponse in SessionManager
  -> MainActivity
  -> Home shows authenticated username

LoginActivity
  -> RegisterActivity
  -> POST /api/auth/register
  -> return to LoginActivity
  -> prefill registered username

Home Logout
  -> confirmation dialog
  -> clear SessionManager
  -> clear Activity stack
  -> LoginActivity

Login Forgot password
  -> ForgotPasswordActivity
  -> POST /api/auth/forgot-password
  -> success returns to Login with username prefilled

Home Reset password
  -> ResetPasswordActivity
  -> PUT /api/users/me/password with Bearer token
  -> success clears the old Session
  -> LoginActivity with username prefilled
```

App startup behavior:

1. `LoginActivity` checks for an unexpired local Session.
2. A valid Session opens `MainActivity` directly.
3. An absent or expired Session stays on Login.
4. `MainActivity` also checks Session before displaying authenticated content.
5. Any HTTP 401 received through the shared OkHttp client clears the local Session.

## 4. Implemented API Contract

### 4.1 Login

```http
POST /api/auth/login
Content-Type: application/json
```

Request:

```json
{
  "username": "ashley.tan",
  "password": "travel123"
}
```

Successful response expected by Android:

```json
{
  "accessToken": "<jwt>",
  "tokenType": "Bearer",
  "expiresIn": 86400000,
  "userId": 1,
  "username": "ashley.tan",
  "role": "ROLE_EMPLOYEE"
}
```

Important details:

- The success body is a direct `LoginResponse`, not an `ApiResponse<LoginResponse>` wrapper.
- `expiresIn` is interpreted as milliseconds. `86400000` equals 24 hours.
- Android stores `accessToken`, `tokenType`, expiry time, `userId`, `username`, and `role`.
- The password is never stored by the Android application.

### 4.2 Register

```http
POST /api/auth/register
Content-Type: application/json
```

Request:

```json
{
  "username": "ashley.tan",
  "password": "travel123",
  "email": "ashley.tan@company.com.sg",
  "department": "Sales",
  "phone": "+65 8123 4567"
}
```

`email`, `department`, and `phone` are optional. Android sends JSON `null` when an optional value is blank.

Successful response expected by Android:

```json
{
  "message": "Registration successful"
}
```

Android-side validation mirrors the backend DTO constraints:

| Field | Android rule |
|---|---|
| `username` | Required, 3-50 characters |
| `password` | Required, 8-100 characters |
| Confirm password | Required, must match password; frontend-only field |
| `email` | Optional, but must be a valid email when provided |
| `department` | Optional |
| `phone` | Optional |

### 4.3 Error Response

Android currently parses this backend error structure:

```json
{
  "code": 401,
  "message": "Invalid username or password",
  "data": null
}
```

Status handling:

| HTTP result | Android behavior |
|---|---|
| 401 | Invalid credentials or expired authorization; clear stored Session |
| Other 4xx | Validation/request error; display backend `message` when available |
| 5xx | Server error with Retry action |
| Connection/timeout failure | Network error with Retry action |
| Invalid/unexpected body | Generic safe error message |

All future protected endpoints must accept:

```http
Authorization: Bearer <accessToken>
```

`AuthInterceptor` adds this header automatically whenever a valid Session exists.

### 4.4 Stage 1 Full API Contract Foundation

Stage 1 已按 `Swagger_API_Docs.pdf` 建立完整 Android 接口声明和共享模型，但没有在本阶段替换现有业务页面的 Mock 数据。

| Area | Retrofit methods | Exact paths |
|---|---:|---|
| Auth | 3 | `/api/auth/login`, `/api/auth/register`, `/api/auth/forgot-password` |
| Trip | 7 | `/api/trips`, `/api/trips/{id}`, `/api/trips/{id}/detail`, `/api/trips/{id}/agent-chat` |
| Agent | 4 | `/api/agent/extract-requirements`, `/api/agent/generate-itinerary`, `/api/agent/modify-itinerary`, `/api/agent/tasks/{taskId}` |
| Booking | 4 | `/api/bookings`, `/api/bookings/{id}`, `/api/bookings/{tripId}`, `/api/bookings/{id}/cancel` |
| Expense | 4 | `/api/expenses`, `/api/expenses/{id}`, `/api/expenses/{tripId}`, `/api/expenses/upload-receipt` |
| User | 3 | `/api/users/me`, `/api/users/me/password` |
| ML | 1 | `/api/ml/predict-hotel-price` |

Contract handling rules:

- Supports direct DTO responses and `{code, message, data}` wrapper responses.
- Uses `BigDecimal` for money and ISO-format strings for backend dates/times.
- Keeps Agent generation/modification payloads dynamic with Gson `JsonObject` because Swagger does not define a stable result schema.
- Sends receipt files as multipart data and Swagger-documented receipt metadata as query parameters.
- Classifies HTTP 401, 403, 404, 409, 422, other 4xx, 5xx, network, and malformed-response failures centrally.
- A 401 clears the local Session and actively returns any protected Activity to Login.

Password contract note:

- `POST /api/auth/forgot-password` is a new team-agreed contract and still requires matching backend implementation.
- `PUT /api/users/me/password` is the existing authenticated Swagger path and is extended by the agreed Reset Password request fields.
- Confirm-password values are validated only in Android and are not sent to the backend.
- Both Android forms require `username`, `email`, `department`, `phone`, and matching new-password entries; Reset additionally requires the current password.
- Username, required email, phone formatting, password length, and password matching are validated before any request is sent.

### 4.5 Stage 3 Trip Integration

Stage 3 已将 Home、Trips、Trip Request 和 Trip Detail 从单一内存 Trip 切换到真实 Trip API：

| Android action | HTTP contract | UI result |
|---|---|---|
| Create Trip | `POST /api/trips` | 保存后端 `id/status`，并进入真实 Agent itinerary 生成流程 |
| Load Trip list | `GET /api/trips` | Home 显示最近的有效 Trip；Trips 显示完整远程列表 |
| Load one Trip | `GET /api/trips/{id}` | Repository 支持按 ID 读取，供后续流程复用 |
| Load itinerary detail | `GET /api/trips/{id}/detail` | 显示后端 itinerary/day/item；空列表显示真实空状态，不生成 Mock 时间线 |
| Edit Trip | `PUT /api/trips/{id}` | 复用 Trip Request XML 表单并刷新详情 |
| Cancel Trip | `DELETE /api/trips/{id}` | 二次确认、真实请求、成功后返回列表 |
| Request Agent change | `POST /api/trips/{id}/agent-chat` | 显示后端接受的 `taskId/status` |

Trip request JSON follows Swagger exactly: `title`, `destination`, `startDate`, `endDate`, `budgetTotal`, and `preferences`. Android derives the default title as `<city> Business Trip`, sends ISO `yyyy-MM-dd` dates, and uses decimal JSON money.

Swagger Trip requests do not contain `notes`, and the current backend does not return `preferences/notes` in `TripDTO`. Android therefore stores only these two form-only values in app-private `SharedPreferences`, keyed by remote Trip ID. Remote `id`, dates, budget, status, and itinerary remain backend data.

The Agent chat endpoint starts an asynchronous task only. Android waits for a terminal task state and never treats `PROCESSING` as success.

### 4.6 Stage 4 Agent Itinerary Lifecycle

Stage 4 uses the persistence-safe backend route instead of calling the three direct Agent generation endpoints:

```text
POST /api/trips/{tripId}/agent-chat
  -> save taskId in app-private SharedPreferences
  -> GET /api/agent/tasks/{taskId} every 2 seconds
  -> PROCESSING: continue waiting
  -> DONE / ITINERARY_READY: clear taskId and reload GET /api/trips/{tripId}/detail
  -> DONE / NEEDS_MORE_INFO: show clarifyingQuestion
  -> FAILED / invalid response: show honest failure and Retry
  -> network interruption / 90-second timeout: retain taskId so Retry resumes the same task
```

Initial generation and modification messages always include origin Singapore, destination, ISO dates, SGD budget, preferences, notes, and—when modifying—the requested change. This is required because the backend extracts complete trip requirements from every Agent-chat message.

`ItineraryReviewActivity` now:

- checks the persisted Trip detail first and skips generation when an itinerary already exists;
- starts or resumes the background Agent task when the itinerary is empty;
- maps the persisted first flight, first hotel, first other item, item prices, and budget into the prototype-based XML cards;
- hides absent cards instead of inventing flight/hotel content;
- converts the backend's raw Agent JSON description into readable item text;
- keeps booking explicitly labelled as a simulation and loads the ML hotel-price estimate only when a HOTEL item exists.

`TripDetailActivity` now automatically resumes an active task, disables duplicate Agent submissions, reloads the day timeline after success, and exposes Retry for timeout/network/task failures. Activity recreation does not create a duplicate task because the active task ID is stored by remote Trip ID.

### 4.7 Stage 5 Booking Integration

Stage 5 connects the prototype-based Mock Booking flow to the current Spring Boot Booking records without claiming a real airline, hotel, payment, or external provider transaction:

```text
Confirm mock booking
  -> GET /api/bookings
  -> reuse an active FLIGHT/HOTEL record for this Trip when present
  -> POST /api/bookings/{tripId} only for each missing type
  -> open confirmation with returned Booking IDs
  -> GET /api/bookings/{id} for current detail/status
  -> optional confirmed cancellation
  -> PUT /api/bookings/{id}/cancel for every active record
  -> GET /api/bookings/{id} again to display CANCELLED
```

The create request follows the backend controller exactly:

```json
{
  "type": "FLIGHT",
  "bookingRef": "MOCK-FLT-7-20260818",
  "price": 412.50,
  "currency": "SGD"
}
```

- Android creates only types that actually exist in the persisted Agent itinerary: `FLIGHT` and/or `HOTEL`.
- Existing non-`CANCELLED`/non-`FAILED` records are reused before any `POST`, so Retry after a partial failure does not normally duplicate the successful record.
- An Agent-provided `bookingRef` is preserved. When absent, Android generates a deterministic `MOCK-FLT-...` or `MOCK-HTL-...` reference because the current backend requires the frontend to supply one.
- Backend `id`, `bookingRef`, `status`, `price`, and `currency` are displayed. Fields absent from `BookingDTO`, such as real terminal, room, provider, or payment details, are not invented.
- Loading, empty/error, Retry, cancellation confirmation, mixed-currency, missing-price, and `CANCELLED` states are represented in the existing XML confirmation screen.
- The confirmation notice explicitly says records are saved by TravelHub while no external provider is contacted.

### 4.8 Stage 6 Expense, Receipt, and Claim Status

The receipt upload endpoint both stores the file and creates the Expense. Android validates a Photo Picker or Camera JPG/PNG up to 10 MB, calls POST /api/expenses/upload-receipt once, uses the wrapped data as the created Expense, and opens GET /api/expenses/{id} for Claim Status.

Multipart fields are file, tripId, category, amount, currency, and description. Categories are FLIGHT, HOTEL, MEAL, TRANSPORT, or OTHER. Merchant, expense date, and notes are encoded into the readable description because the backend DTO does not provide separate merchant/date fields.

Expenses and Claims both call GET /api/expenses and support multiple records plus Loading/Empty/Error/Retry. No separate Claim endpoint exists; SUBMITTED, APPROVED, and REJECTED are rendered directly from Expense status. Receipt URLs can be absolute or resolved relative to API_BASE_URL.

### 4.9 Stage 7 User Profile

ProfileActivity calls wrapped GET /api/users/me and PUT /api/users/me. Username and role are read-only. Only email, department, and phone are editable and included in the update body. The screen provides validation, Loading/Error/Retry, save feedback, Reset Password, and confirmed Logout.

### 4.10 Stage 8 ML Hotel-Price Prediction

Itinerary Review calls POST /api/ml/predict-hotel-price only when the persisted itinerary contains a HOTEL item. Android sends the documented camelCase request and accepts both documented camelCase and current Python-proxy snake_case response fields.

The hotel card shows price/night, total, nights, returned currency, model status/version, and whether isMock is true. API failures show a tap-to-Retry message and never fall back to a local predicted value. The current Python mock contract documents USD-only support, so the MVP requests and visibly displays USD.

## 5. Network Configuration

The Base URL is generated into `BuildConfig.API_BASE_URL` from `app/build.gradle.kts`.

Default:

```text
http://10.0.2.2:8080/
```

Use the following address based on the test environment:

| Environment | Base URL example |
|---|---|
| Android Emulator and backend on the same computer | `http://10.0.2.2:8080/` |
| Physical Android device | `http://<backend-computer-LAN-IP>:8080/` |
| Deployed backend | `https://<server-domain>/` |

The Base URL must end with `/`.

To override it for a command-line build:

```bash
./gradlew -PAPI_BASE_URL=http://192.168.1.20:8080/ assembleDebug
```

For Android Studio, place a developer-specific value in the user's `~/.gradle/gradle.properties`:

```properties
API_BASE_URL=http://192.168.1.20:8080/
```

Do not commit a personal LAN IP, password, JWT secret, or API key. Cleartext HTTP is enabled only to support local course-development testing; a deployed environment should use HTTPS.

## 6. Relevant Source Files

### Network and contract

| File | Responsibility |
|---|---|
| [`network/ApiClient.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/ApiClient.kt) | Retrofit/Gson/OkHttp creation, Base URL, timeouts, debug request logging |
| [`network/AuthApi.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/AuthApi.kt) | Login, Register, and agreed Forgot Password Retrofit endpoints |
| [`network/TripApi.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/TripApi.kt) | Trip CRUD, detailed itinerary, and Trip Agent chat endpoints |
| [`network/AgentApi.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/AgentApi.kt) | Requirement extraction, itinerary generation/modification, and task polling |
| [`network/BookingApi.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/BookingApi.kt) | Mock booking list/detail/create/cancel endpoints |
| [`network/ExpenseApi.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/ExpenseApi.kt) | Expense list/detail/submission and multipart receipt upload |
| [`network/UserApi.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/UserApi.kt) | Current profile, profile update, and authenticated password reset |
| [`network/MlApi.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/MlApi.kt) | Hotel-price prediction endpoint |
| [`network/AuthInterceptor.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/AuthInterceptor.kt) | Bearer header injection, 401 Session clearing, and expiry notification |
| [`network/ApiResult.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/ApiResult.kt) | Shared success/failure result types |
| [`network/ApiCallExecutor.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/ApiCallExecutor.kt) | Network exception and backend error parsing |
| [`network/model/`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/model/) | Shared, Auth, Trip, Agent, Booking, Expense, User, and ML JSON models |

### Repository and Session

| File | Responsibility |
|---|---|
| [`data/repository/AuthRepository.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/AuthRepository.kt) | Login/Register operations and Session coordination |
| [`data/repository/PasswordRepository.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/PasswordRepository.kt) | Forgot/Reset requests and direct/wrapped success handling |
| [`data/repository/TripRepository.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/TripRepository.kt) | All seven Trip requests, DTO mapping, and invalid-response protection |
| [`data/repository/AgentRepository.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/AgentRepository.kt) | Agent task status polling, terminal result parsing, timeout, and transport failures |
| [`data/repository/AgentTripPlanner.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/AgentTripPlanner.kt) | Start/resume coordination, complete Agent messages, and task cleanup rules |
| [`data/repository/BookingRepository.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/BookingRepository.kt) | Booking list/detail/create/cancel mapping, active-record reuse, partial-failure-safe Retry, and invalid-response protection |
| [`data/repository/ExpenseRepository.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/ExpenseRepository.kt) | Expense list/detail/direct-submit support, multipart receipt upload, DTO validation, and description mapping |
| [`data/repository/UserRepository.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/UserRepository.kt) | Wrapped current-profile load/update and invalid-response protection |
| [`data/repository/MlRepository.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/MlRepository.kt) | Hotel prediction request creation, response validation, and no-fallback failure handling |
| [`data/repository/TripMappers.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/TripMappers.kt) | ISO date/time, money, status, Trip, and itinerary UI mapping |
| [`data/local/AgentTaskStore.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/local/AgentTaskStore.kt) | App-private active task ID persistence by remote Trip ID |
| [`data/local/TripDraftStore.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/local/TripDraftStore.kt) | App-private persistence for form-only preferences and notes by remote Trip ID |
| [`data/local/CurrentTripStore.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/local/CurrentTripStore.kt) | Optional in-process hand-off of a real remote Trip; contains no default Jakarta/Mock Trip |
| [`session/SessionManager.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/session/SessionManager.kt) | Save, read, expire, authorize, and clear Session |
| [`session/UserSession.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/session/UserSession.kt) | In-app authenticated user model |
| [`session/SessionEventBus.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/session/SessionEventBus.kt) | Notify visible protected screens when a request returns 401 |
| [`ui/auth/AuthenticatedActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/auth/AuthenticatedActivity.kt) | Shared Session guard and Login redirection for protected Activities |

The current course MVP stores the JWT in app-private `SharedPreferences`. This is sufficient for the agreed demonstration scope but is not presented as production-grade encrypted credential storage.

### UI and validation

| File | Responsibility |
|---|---|
| [`ui/auth/LoginActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/auth/LoginActivity.kt) | Username Login, loading, failure, Retry, Register entry, startup Session routing |
| [`ui/auth/RegisterActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/auth/RegisterActivity.kt) | Register form, validation, request, and return-to-Login result |
| [`ui/auth/ForgotPasswordActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/auth/ForgotPasswordActivity.kt) | Public recovery form, validation, API call, Retry, and Login return |
| [`ui/auth/ResetPasswordActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/auth/ResetPasswordActivity.kt) | Protected reset form, API call, Retry, Session clearing, and re-login flow |
| [`ui/auth/AuthFailureUi.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/auth/AuthFailureUi.kt) | Convert API failures into user-facing messages |
| [`ui/main/MainActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/main/MainActivity.kt) | Authenticated screen protection and main navigation |
| [`ui/home/HomeFragment.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/home/HomeFragment.kt) | Authenticated username, Logout, and remote upcoming Trip state |
| [`ui/profile/ProfileActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/profile/ProfileActivity.kt) | Remote profile load/update, validation, Reset Password, Logout, and UI states |
| [`ui/trips/TripsFragment.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/trips/TripsFragment.kt) | Remote multi-Trip list with loading, empty, error, and Retry states |
| [`ui/trips/TripRequestActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/trips/TripRequestActivity.kt) | Validated Trip create/edit form calling `POST` or `PUT` |
| [`ui/trips/ItineraryReviewActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/trips/ItineraryReviewActivity.kt) | Real Agent generation/resume states and persisted itinerary review cards |
| [`ui/trips/MockBookingConfirmationActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/trips/MockBookingConfirmationActivity.kt) | Remote Booking detail, status/price/reference display, Loading/Error/Retry, and confirmed cancellation |
| [`ui/trips/TripDetailActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/trips/TripDetailActivity.kt) | Remote timeline, edit/cancel, Agent modification polling, Retry, and refresh |
| [`ui/expense/AddExpenseActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/expense/AddExpenseActivity.kt) | Photo Picker/camera receipt capture, validation, and one multipart Expense creation request |
| [`ui/expense/ExpensesFragment.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/expense/ExpensesFragment.kt) | Persistent multi-expense list and Loading/Empty/Error/Retry states |
| [`ui/claims/ClaimsFragment.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/claims/ClaimsFragment.kt) | Expense-status-based Claim list without a fabricated Claim API |
| [`ui/claims/ClaimStatusActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/claims/ClaimStatusActivity.kt) | Remote Expense detail, Trip association, receipt link, Finance status, and Add Another Expense |
| [`validation/InputValidator.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/validation/InputValidator.kt) | Registration and existing trip-form validation |
| [`res/layout/activity_login.xml`](app/src/main/res/layout/activity_login.xml) | Login XML layout |
| [`res/layout/activity_register.xml`](app/src/main/res/layout/activity_register.xml) | Scrollable Register XML layout |
| [`res/layout/activity_forgot_password.xml`](app/src/main/res/layout/activity_forgot_password.xml) | Scrollable Forgot Password XML layout |
| [`res/layout/activity_reset_password.xml`](app/src/main/res/layout/activity_reset_password.xml) | Scrollable Reset Password XML layout |
| [`res/layout/activity_profile.xml`](app/src/main/res/layout/activity_profile.xml) | Scrollable Profile XML layout with remote UI states |
| [`res/layout/item_expense_record.xml`](app/src/main/res/layout/item_expense_record.xml) | Reusable Expense/Claim list card |

### Tests

| File | Responsibility |
|---|---|
| [`ApiErrorParserTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/network/ApiErrorParserTest.kt) | 401 message parsing and malformed-error fallback |
| [`ApiContractTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/network/ApiContractTest.kt) | All 26 methods/paths plus direct, wrapped, and dynamic JSON parsing |
| [`AuthInterceptorTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/network/AuthInterceptorTest.kt) | Bearer header, unauthenticated request, and 401 callback behavior |
| [`PasswordRepositoryTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/PasswordRepositoryTest.kt) | Exact Forgot/Reset paths, request JSON, and frontend-only confirmation boundary |
| [`TripRepositoryTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/TripRepositoryTest.kt) | All seven Trip paths, exact JSON, local fields, detail mapping, and malformed dates |
| [`AgentRepositoryTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/AgentRepositoryTest.kt) | Processing-to-ready, missing information, failure, timeout, invalid status, and 404 task behavior |
| [`AgentTripMessageFactoryTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/AgentTripMessageFactoryTest.kt) | Complete initial/modification messages with all extraction facts |
| [`BookingRepositoryTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/BookingRepositoryTest.kt) | Exact Booking paths/JSON, two-type creation, active-record reuse, detail, cancellation, and malformed-response rejection |
| [`ExpenseRepositoryTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/ExpenseRepositoryTest.kt) | Exact Expense list/detail/multipart behavior, one-request upload, status mapping, and malformed-response rejection |
| [`ExpenseDescriptionCodecTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/ExpenseDescriptionCodecTest.kt) | Merchant/date/notes round-trip and legacy description handling |
| [`UserRepositoryTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/UserRepositoryTest.kt) | Exact Profile GET/PUT paths, editable JSON fields, wrapped mapping, and invalid response |
| [`MlRepositoryTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/MlRepositoryTest.kt) | Exact ML request, camel/snake response compatibility, Mock flag, and no local fallback |
| [`InputValidatorTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/validation/InputValidatorTest.kt) | Username, password, required/optional email, phone, budget, and date validation |

## 7. Build and Verification

From `mobile-frontend/`:

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Latest complete full-suite result after Stage 9:

- Android build: successful.
- Android Lint: successful.
- Unit tests: 56 passed, with zero failures, errors, or skipped tests.
- API contract tests: all 26 methods/paths and supported response shapes passed.
- Password tests: required email/phone validation and exact Forgot/Reset request bodies passed.
- Trip tests: create/list/get/detail/update/delete/chat paths, request JSON, ISO mapping, local fields, and malformed-response handling passed.
- Login/Register layouts checked at `1344x2992 / 480 dpi` and `1080x1920 / 420 dpi`.
- Network-unavailable error and Retry behavior verified for authentication and the Trip list.
- Persisted Session startup, dynamic username greeting, Logout Cancel/Confirm, and Session clearing verified on emulator.
- Controlled-API emulator flow verified successful Trip list/detail, empty itinerary, day switching, create, edit, cancel, Agent task submission, error state, and Retry recovery.
- No Android fatal exception was found in the final emulator log check.

Stage 4 verification:

- Agent repository/message tests and updated Trip mapper tests passed after the data-layer implementation.
- `activity_itinerary_review.xml`, `activity_trip_detail.xml`, and `strings.xml` pass XML parsing.
- `git diff --check` passes.
- `./gradlew assembleDebug lintDebug testDebugUnitTest` completed with `BUILD SUCCESSFUL`; all 46 tests passed and the Debug APK was generated.
- A controlled emulator service previously verified real Username Login and exact Trip creation JSON for London. The full Agent Review/modification success chain still requires the Agent/shared backend and is not recorded as DigitalOcean or real Agent-service UAT.

Stage 5 verification:

- All 4 focused `BookingRepositoryTest` cases pass after compiling the new Repository, UI Activities, ViewBinding classes, and XML resources.
- `activity_itinerary_review.xml`, `activity_mock_booking_confirmation.xml`, and `strings.xml` pass XML parsing.
- `git diff --check` passes.
- The Stage 9 full `assembleDebug lintDebug testDebugUnitTest` regression now includes Stage 5 and completed with BUILD SUCCESSFUL.
- A real Spring Boot/DigitalOcean Booking success path and emulator UI walkthrough remain joint-UAT work; local tests verify Android request, mapping, deduplication, state, and cancellation behavior only.

Stages 6-9 verification:

- Expense codec/repository, User repository, and ML repository focused tests all pass, including exact paths/request bodies, multipart single-creation behavior, wrapped Profile responses, camel/snake ML response compatibility, and malformed-response rejection.
- Obsolete local Expense/Claim/Itinerary Mock generators and their obsolete tests were removed. The optional CurrentTripStore contains no default Jakarta record.
- Every Android XML resource parses successfully and git diff --check passes.
- The final Debug APK installed and launched on Pixel_10_Pro_XL_2. Standard 1344x2992 / 480 dpi and compact 1080x1920 / 420 dpi screens showed reachable Home/Profile/Expenses content, correct network Error/Retry states, fixed bottom navigation, and no fatal Android exception.
- Shared service success paths for Expense upload, Finance status, Profile update, ML response, Booking, and Agent completion still require joint team UAT with credentials and running services.

The controlled emulator checks verify Android build, layout, navigation, and failure states only. Spring Boot, MySQL/DigitalOcean persistence, Python ML, and real Agent completion still require joint team UAT.

## 8. Backend Prerequisites

The Spring Boot service is expected on port `8080`, with MySQL available and JWT configuration loaded. The repository root README contains the general backend startup procedure.

Current local-development example from the repository root:

```bash
docker compose up -d mysql
./gradlew :mobile-api:bootRun --args='--spring.profiles.active=dev'
```

Repository note: the root `gradle/wrapper/gradle-wrapper.jar` is currently absent. Until it is restored, use an installed compatible Gradle 8.x runtime or restore the wrapper JAR before using the root `./gradlew`. The separate `mobile-frontend/gradlew` works and was used for Android verification.

Before Android testing, verify one of these:

```text
http://localhost:8080/actuator/health
http://localhost:8080/swagger-ui.html
```

## 9. Backend Integration Attention Points

The following points come from the current backend source and should be checked during merge review:

1. `AuthController` currently receives `LoginRequest` and `RegisterRequest` without `@Valid`. DTO annotations such as `@NotBlank`, `@Size`, and `@Email` may therefore not run at the controller boundary. Android validates these fields, but the backend should still validate independent clients.
2. Duplicate username registration currently throws a generic `RuntimeException`. `GlobalExceptionHandler` converts it to HTTP 500 with `Internal server error`. A specific 400 or 409 business error would give Android a more accurate message.
3. Auth success responses are direct objects/maps, while error responses use `{code, message, data}`. Android intentionally matches this current mixed structure; changing it to a unified wrapper requires coordinated Android model changes.
4. New registered users default to `EMPLOYEE`; Login returns the role as `ROLE_EMPLOYEE`.
5. `expiresIn` is currently returned as `86400000` milliseconds. Coordinate any future change in unit or refresh-token behavior with Android.
6. Native Android does not require browser CORS configuration. Physical-device testing does require network reachability and local firewall access to port `8080`.
7. `BookingService` accepts only exact enum values `FLIGHT` and `HOTEL`; Android sends these uppercase values. The backend defaults missing currency to `CNY`, while Android deliberately sends the itinerary currency or `SGD`.
8. `BookingDTO` does not include flight/hotel provider details, and the backend does not create real supplier bookings. Android therefore displays only persisted record fields and keeps the simulation notice visible.
9. `POST /api/expenses/upload-receipt` already creates and returns an Expense. Do not add a second direct Expense submission after upload success or one receipt will create duplicate records.
10. There is no Claim controller. Claims UI must continue to read Expense `status` rather than introducing a mobile-only Claim identifier or endpoint.
11. Profile update accepts only `email`, `department`, and `phone`. Android keeps `username` and `role` read-only.
12. The Spring ML proxy converts request keys to snake_case before Python but currently returns the Python Map directly. Android accepts both camelCase and snake_case response fields. The current Python mock documents USD-only input.
13. No Personalized Recommendations endpoint exists in Swagger or the backend source. Android shows an explicit unavailable/P1 boundary and does not fabricate recommendation results.

## 10. Joint Integration Test Checklist

Run these checks before merging the frontend integration change:

- [ ] MySQL starts and the Spring Boot health endpoint responds.
- [ ] Android uses the correct Base URL for the emulator/device.
- [ ] A new valid Employee account registers successfully.
- [ ] Register returns to Login and prefills the registered username.
- [ ] Duplicate username returns a deliberate client error rather than an unexplained 500.
- [ ] Correct username/password opens Home and displays the backend username.
- [ ] Wrong password returns HTTP 401 with a readable message.
- [ ] Closing and reopening the app preserves an unexpired Session.
- [ ] Logout clears the Session and returns to Login.
- [ ] Reopening the app after Logout does not enter Home.
- [ ] A protected endpoint accepts the automatically attached Bearer token.
- [ ] An expired/invalid token returns 401 and clears the Android Session.
- [ ] Forgot Password accepts the five agreed request fields and returns a deliberate success/error response.
- [ ] Reset Password accepts the six agreed request fields with Bearer authorization and invalidates or safely handles the previous credentials.
- [ ] Both password flows reject mismatched identity details and incorrect current passwords with readable client errors.
- [ ] Trip create returns a real ID and the same Trip appears in `GET /api/trips`.
- [ ] Home and Trips show backend destination, dates, budget, and status for the authenticated user only.
- [ ] Trip detail returns ordered itineraries/items or an honest empty list.
- [ ] Trip edit persists after reopening the detail page.
- [ ] Trip cancellation returns the documented message and removes/marks the Trip consistently.
- [ ] Trip Agent chat returns HTTP 202 with a non-empty `taskId` and `status`.
- [ ] Agent task returns `PROCESSING` before a terminal result without creating duplicate tasks.
- [ ] `DONE / ITINERARY_READY` persists itinerary rows and Android refreshes both Review and Trip Detail.
- [ ] `DONE / NEEDS_MORE_INFO` shows the backend clarifying question.
- [ ] `FAILED`, timeout, server interruption, and Retry do not display Mock itinerary as success.
- [ ] Reopening the Activity resumes the stored task ID rather than starting a second task.
- [ ] Confirm Mock Booking first calls the user Booking list and creates only missing `FLIGHT`/`HOTEL` records for the owned Trip.
- [ ] Repeating confirmation reuses active records rather than creating duplicate Booking rows.
- [ ] Booking detail returns the same `id`, `tripId`, `type`, `bookingRef`, `price`, `currency`, and `status` shown by Android.
- [ ] Cancel Mock Booking changes every active record to `CANCELLED`, and reopening the confirmation page displays the refreshed status.
- [ ] Booking failures show Loading/Error/Retry without falling back to local Jakarta or fabricated provider data.
- [ ] Camera and Photo Picker receipts reject non-JPG/PNG and files larger than 10 MB.
- [ ] One receipt upload creates exactly one Expense and returns the ID opened by Claim Status.
- [ ] Expenses and Claims show all owned records with the backend SUBMITTED/APPROVED/REJECTED status.
- [ ] Relative receipt URLs open against the configured API Base URL.
- [ ] Profile loads the authenticated user, keeps username/role read-only, and persists email/department/phone changes.
- [ ] ML prediction is requested only for an itinerary containing a HOTEL item and displays isMock, returned currency, prices, nights, and model metadata.
- [ ] Expense/Profile/ML network and server failures expose Retry and never display old local Mock results.
- [ ] `./gradlew assembleDebug lintDebug testDebugUnitTest` still passes after merge conflict resolution.

## 11. Known Limitations and Next Integration Order

- No refresh-token endpoint or automatic token refresh is implemented. Users log in again after expiry.
- Forgot Password and Reset Password are complete on Android, but the new Forgot path and extended Reset request body still require matching Spring Boot implementation and DigitalOcean integration testing.
- Successful live authentication and Trip persistence still require joint testing with the running Spring Boot service and DigitalOcean database.
- Agent Trip generation/modification task polling and result refresh are implemented; live Agent-service/DigitalOcean UAT is still pending.
- Booking list/detail/create/cancel is connected on Android, but Spring Boot/MySQL/DigitalOcean joint UAT remains pending.
- Expense, Receipt, Claim-status, User Profile, and ML screen integration is implemented on Android. Shared Spring Boot/MySQL/Python/DigitalOcean and emulator UAT is still required.
- Personalized Recommendations remains unavailable because there is no API contract. It is an honest P1 boundary rather than a local Mock feature.
- OCR is not implemented. The course MVP intentionally uses receipt image upload plus corrected manual merchant/date/amount/category/notes fields.

Remaining integration/verification order:

1. Backend owner implements and documents the agreed Forgot/Reset contracts; run joint password-flow UAT.
2. Complete live Stage 4 Agent task UAT against the shared backend/Agent service.
3. Run Booking creation/list/detail/cancel UAT against Spring Boot/MySQL/DigitalOcean.
4. Run Expense upload/list/detail/Finance-status UAT and verify uploaded receipt access from the Web side.
5. Run Profile update and Spring-to-Python ML prediction UAT.
6. Complete final end-to-end stabilization, screenshots, and demo dataset preparation.

## 12. Merge Boundary

The current uncommitted frontend change contains Stages 1-9:

- shared network and error-handling foundation;
- Swagger-aligned 26-method API contract and shared models;
- JWT Session persistence and Bearer header handling;
- protected-screen redirection after HTTP 401;
- Username Login, Register, Forgot Password, and Reset Password UI;
- authenticated startup routing and Logout;
- complete seven-method Trip Repository and six direct screen flows;
- remote Home/Trips/detail UI states and Trip create/edit/cancel;
- Agent task persistence/polling, real itinerary review, modification refresh, and honest terminal/error states;
- Booking Repository, active-record reuse, real Booking confirmation/detail/cancel calls, and clearly labelled simulation UI;
- real receipt/camera upload, multi-expense history, Expense-detail Claim status, and no duplicate Expense creation;
- Profile load/update with read-only account identity and editable contact fields;
- hotel-price prediction on the itinerary HOTEL card with isMock/model/currency display and no local fallback;
- removal of obsolete Jakarta/Expense/Itinerary Mock generators and explicit Recommendations API boundary;
- focused tests and Android resources.

Direct Agent generation endpoints remain declared but are intentionally not used for the persistence-safe Trip flow. Android screen integration is complete for the agreed Traveler MVP, but shared-backend, Agent, Booking, Expense, Profile, ML, and emulator UAT is still required before release. No Commit or Push is performed automatically by the frontend implementation workflow; the branch owner controls final Git publication.
