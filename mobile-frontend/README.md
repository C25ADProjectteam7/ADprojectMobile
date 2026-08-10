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
| Trip / Itinerary / Booking | 页面和 Mock 流程已实现 | 仍为本地 Mock 数据 |
| Expense / Claim | 页面和 Mock 流程已实现 | 仍为本地 Mock 数据 |
| Agent / ML / User Profile | 尚未接入 | 后续阶段 |
| Forgot / Reset Password | 暂不实现 | 当前 Sprint 范围外 |

因此，本次合并和联调应首先聚焦 Login、Register、Session 和 Logout，不应把其他 Mock 页面误认为已经完成后端集成。

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
| [`network/AuthApi.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/AuthApi.kt) | Exact Login and Register Retrofit endpoints |
| [`network/AuthInterceptor.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/AuthInterceptor.kt) | Bearer header injection and 401 Session clearing |
| [`network/ApiResult.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/ApiResult.kt) | Shared success/failure result types |
| [`network/ApiCallExecutor.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/ApiCallExecutor.kt) | Network exception and backend error parsing |
| [`network/model/`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/network/model/) | Login/Register/error JSON models |

### Repository and Session

| File | Responsibility |
|---|---|
| [`data/repository/AuthRepository.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/data/repository/AuthRepository.kt) | Login/Register operations and Session coordination |
| [`session/SessionManager.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/session/SessionManager.kt) | Save, read, expire, authorize, and clear Session |
| [`session/UserSession.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/session/UserSession.kt) | In-app authenticated user model |

The current course MVP stores the JWT in app-private `SharedPreferences`. This is sufficient for the agreed demonstration scope but is not presented as production-grade encrypted credential storage.

### UI and validation

| File | Responsibility |
|---|---|
| [`ui/auth/LoginActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/auth/LoginActivity.kt) | Username Login, loading, failure, Retry, Register entry, startup Session routing |
| [`ui/auth/RegisterActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/auth/RegisterActivity.kt) | Register form, validation, request, and return-to-Login result |
| [`ui/auth/AuthFailureUi.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/auth/AuthFailureUi.kt) | Convert API failures into user-facing messages |
| [`ui/main/MainActivity.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/main/MainActivity.kt) | Authenticated screen protection and main navigation |
| [`ui/home/HomeFragment.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/ui/home/HomeFragment.kt) | Authenticated username and Logout flow |
| [`validation/InputValidator.kt`](app/src/main/java/iss/nus/edu/sg/viewbinding/caproject/validation/InputValidator.kt) | Registration and existing trip-form validation |
| [`res/layout/activity_login.xml`](app/src/main/res/layout/activity_login.xml) | Login XML layout |
| [`res/layout/activity_register.xml`](app/src/main/res/layout/activity_register.xml) | Scrollable Register XML layout |

### Tests

| File | Responsibility |
|---|---|
| [`ApiErrorParserTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/network/ApiErrorParserTest.kt) | 401 message parsing and malformed-error fallback |
| [`InputValidatorTest.kt`](app/src/test/java/iss/nus/edu/sg/viewbinding/caproject/validation/InputValidatorTest.kt) | Username, password, optional email, budget, and date validation |

## 7. Build and Verification

From `mobile-frontend/`:

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Latest verified result:

- Android build: successful.
- Android Lint: successful.
- Unit tests: 22 passed.
- Login/Register layouts checked at `1344x2992 / 480 dpi` and `1080x1920 / 420 dpi`.
- Network-unavailable error and Retry behavior verified against `/api/auth/login`.
- Persisted Session startup, dynamic username greeting, Logout Cancel/Confirm, and Session clearing verified on emulator.
- No Android fatal exception was found in the final emulator log check.

Live Register/Login success has not yet been verified because no MySQL/backend service was running in the frontend test environment.

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

## 10. Joint Integration Test Checklist

Run these checks before merging the authentication change:

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
- [ ] `./gradlew assembleDebug lintDebug testDebugUnitTest` still passes after merge conflict resolution.

## 11. Known Limitations and Next Integration Order

- No refresh-token endpoint or automatic token refresh is implemented. Users log in again after expiry.
- Forgot Password and Reset Password are deliberately deferred.
- Successful live authentication still requires joint testing with the running backend and database.
- Trip, Agent, Booking, Expense, Receipt, Claim, User Profile, and ML interfaces are not yet connected to the Android screens.
- Current Trip and Expense demonstrations use clearly identified local Mock data.

Planned API integration order:

1. Trip create/list/get/update/cancel.
2. Agent itinerary generation, task polling, and modification.
3. Mock booking creation/list/detail/cancel.
4. Receipt upload and expense submission/list/detail.
5. Claim-status refresh.
6. ML hotel-price prediction.

## 12. Merge Boundary

This frontend change should be reviewed as one authentication unit:

- shared network and error-handling foundation;
- Swagger-aligned Login/Register models and endpoints;
- JWT Session persistence and Bearer header handling;
- Username Login and Register UI;
- authenticated startup routing and Logout;
- focused tests and Android resources.

Do not treat the remaining Mock business screens as part of the authentication merge acceptance criteria. No Commit or Push is performed automatically by the frontend implementation workflow; the branch owner controls final Git publication.
