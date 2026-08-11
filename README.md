# Team7 Mobile Platform — 智能商旅规划与差旅报销平台

## 项目简介

Smart Travel & Expense Hub 的 Mobile 端后端服务，为 Android 客户端和 Agentic AI 提供 REST API。

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 运行时 | Java (OpenJDK) | 17 |
| 框架 | Spring Boot | 3.3.x |
| 构建 | Gradle | 8.x |
| 数据库 | MySQL | 8.0 |
| ORM | Spring Data JPA (Hibernate) | |
| 安全 | Spring Security + JWT (JJWT) | |
| API 文档 | SpringDoc OpenAPI | |
| LLM | DeepSeek (OpenAI 兼容) | |
| ML | Scikit-learn + XGBoost | |
| 容器 | Docker + Docker Compose | |
| CI/CD | GitHub Actions | |

## 项目结构

```
ADprojectMobile/
├── mobile-common/          # 公共模块：DTO、异常、工具类、常量
├── mobile-security/        # 安全模块：JWT、Spring Security
├── mobile-data/            # 数据层：JPA Entity、Repository
├── mobile-business/        # 业务逻辑：行程、预订、Agent 编排、外部 API
├── mobile-api/             # REST API 入口：Controller、OpenAPI
├── agent-ml-service/       # Python Agent + ML 服务（FastAPI）
├── docker/                 # Docker 配置
│   ├── mysql/init.sql      # 数据库建表脚本
│   └── nginx/nginx.conf    # Nginx 反向代理配置
├── docker-compose.yml      # 容器编排
├── Dockerfile              # Spring Boot 镜像
└── .github/workflows/      # CI/CD Pipeline
```

## 快速开始

### 前置条件
- JDK 17+
- Docker & Docker Compose
- Gradle 8.x（或使用 ./gradlew）

### 本地开发

```bash
# 1. 启动 MySQL
docker compose up -d mysql

# 2. 启动 Spring Boot
./gradlew :mobile-api:bootRun --args='--spring.profiles.active=dev'

# 3. 启动 Python Agent 服务（另开终端）
cd agent-ml-service
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload

# 4. 访问 Swagger UI
open http://localhost:8080/swagger-ui.html
```

### Docker 部署

```bash
# 复制并配置环境变量
cp .env.example .env
# 编辑 .env 填入真实 API Key

# 构建并启动所有服务
docker compose up -d --build

# 查看服务状态
docker compose ps
```

## API 概览

| 端点 | 描述 | 认证 |
|------|------|------|
| POST /api/auth/register | 用户注册 | 公开 |
| POST /api/auth/login | 用户登录 | 公开 |
| POST /api/trips | 创建行程（Agent 自动规划） | 需认证 |
| GET /api/trips | 获取行程列表 | 需认证 |
| POST /api/agent/chat | 与 Agent 对话修改行程 | 需认证 |
| POST /api/bookings/flight | 预订航班 | 需认证 |
| POST /api/expenses | 提交报销 | 需认证 |
| POST /api/expenses/upload-receipt | 上传发票 OCR | 需认证 |

## Machine Learning — Hotel Price Prediction

**当前状态（2026-08-11 更新）：真实训练的 baseline 模型，不再是 mock。** 详见
[API Contract](docs/ml/hotel-price-api-contract.md) 和
[Baseline Results](docs/ml/hotel-price-baseline-results.md)。

`agent-ml-service/ml/price_predictor.py` 里的 `HotelPricePredictor` 加载
`models/hotel_price_baseline.joblib`（RandomForest，训练自 Hotel Booking
Demand 数据集）做真实推理。**重要限制：`city`、`hotel_star_rating`、
`room_type` 这三个字段目前对预测结果没有任何影响**——训练数据里根本没有这些
信息，不是没做好，是数据集里从来没有过。只有日期范围和 `number_of_guests`
真正影响预测值。`MockHotelPricePredictor` 还留在同一个文件里供参考/测试，
但线上路由已经不用它了。

**当前只支持 `currency=USD`**（大小写不敏感，会被归一化为大写；其他 currency 返回 422）——训练数据没有标注货币（推测是 EUR，未做转换），直接 echo 其他 currency 会误导调用方。

### 安装依赖

```bash
cd agent-ml-service
pip install -r requirements.txt
```

### 启动 FastAPI

```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

### Health-check

```bash
curl http://localhost:8000/health
# {"status": "healthy", "service": "agent-ml-service"}
```

### Hotel Price Prediction 端点

```bash
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
```

响应示例：

```json
{
  "predicted_price_per_night": 120.65,
  "predicted_total_price": 361.95,
  "number_of_nights": 3,
  "currency": "USD",
  "model_status": "baseline",
  "model_version": "baseline-rf-v1",
  "is_mock": false,
  "message": "BASELINE model (RandomForest trained on the Hotel Booking Demand dataset). Only lead time, stay length, guest count, and arrival month currently affect this prediction. city, room_type, and other inputs are accepted by the API but NOT used by this model — the training dataset has no city or star-rating data, and room_type has no verified mapping to the dataset's room codes. Do not treat this as reflecting real city or room-type price differences."
}
```
（这是真实跑出来的响应，不是手算的示例；数值比旧 mock 示例低是因为底层数据/模型换了，不是 bug）

### 运行测试

```bash
cd agent-ml-service
pytest tests/ -v
```

当前结果：**23 个测试全部通过**（健康检查、正常请求、baseline 标记、真实预测数值合法性、输入校验 422、currency/city 边界情况、结果确定性、model artifact 加载、OpenAPI schema 存在性）。验证环境为本机现有 `.venv`，尚未在 `requirements.txt` 锁定版本或 Docker 目标环境下验证。

### 模型训练与未来改进

训练代码在 `agent-ml-service/training/`（`inspect_dataset.py` + `train_baseline.py`），产出 `models/hotel_price_baseline.joblib`（已提交进 git，原始数据集 CSV 已 gitignore）。完整的数据集选型、预处理、特征表、评估结果、已知限制见
[Baseline Results](docs/ml/hotel-price-baseline-results.md)。下一步改进方向是让 `city`/`hotel_star_rating` 真正影响预测——需要一个有这些字段的新数据源，而不是调参当前模型。

## CI/CD Pipeline

6 阶段流水线（Sprint 2 起部署阶段启用）：
1. **Build** — 编译 + 代码风格检查
2. **Test** — 单元测试 + 覆盖率
3. **SAST** — SpotBugs + OWASP Dependency Check
4. **Docker Build** — 构建镜像 + Trivy 漏洞扫描
5. **Deploy** — SSH 部署到 Digital Ocean

## 安全

- 密码：BCrypt 加密存储
- API：JWT Bearer Token 认证
- 数据：MySQL SSL 连接 + 传输加密
- 依赖：OWASP Dependency Check 持续扫描
- 镜像：Trivy 扫描容器 CVE
- 遵循 OWASP Top 10 安全实践

## 团队

- Team7 Mobile 组 — C25ADProjectteam7
- 负责：Agentic AI、Android 前端、ML、后端基础设施
