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

**当前状态：Mock 实现，非真实 ML 预测。** 详见 [API Contract](docs/ml/hotel-price-api-contract.md) 和 [Dataset Requirements](docs/ml/hotel-price-dataset-requirements.md)。

`agent-ml-service/ml/` 提供一个确定性的规则式（rule-based）Mock predictor：城市基价 × 星级倍数 × 房型倍数。响应中 `is_mock=true`、`model_status="mock"` 明确标注，不得当作真实模型预测结果展示给用户。目前尚未选定/下载/验证正式训练数据集。

**当前 mock 阶段仅支持 `currency=USD`**（大小写不敏感，会被归一化为大写；其他 currency 返回 422）——因为城市基价是 USD 数值，没有做汇率转换，直接 echo 其他 currency 会误导调用方。

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
  "predicted_price_per_night": 224.0,
  "predicted_total_price": 672.0,
  "number_of_nights": 3,
  "currency": "USD",
  "model_status": "mock",
  "model_version": "mock-v0",
  "is_mock": true,
  "message": "MOCK prediction only — based on fixed lookup tables, not a trained model. Do not use this result for real booking decisions."
}
```

### 运行测试

```bash
cd agent-ml-service
pytest tests/ -v
```

当前结果：**21 个测试全部通过**（健康检查、正常请求、mock 标记、精确公式数值、输入校验 422、currency/city 边界情况、结果确定性、OpenAPI schema 存在性）。验证环境为本机现有 `.venv`，尚未在 `requirements.txt` 锁定版本或 Docker 目标环境下验证。

### 未来真实模型替换

当训练数据和模型就绪后，实现 `RealHotelPricePredictor`（与 `MockHotelPricePredictor` 相同的 `predict()` 接口），并在 `ml/routes.py` 中替换引用即可——API 请求/响应结构无需变动。

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
