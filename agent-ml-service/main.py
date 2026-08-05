"""
Agent + ML Service — FastAPI application entry point
=====================================================
Start: uvicorn main:app --host 0.0.0.0 --port 8000 --reload

Provides two capabilities to the Spring Boot backend:
1. Agent brain: trip planning via DeepSeek LLM + tool calling
2. ML inference: price prediction + intelligent budget allocation

API endpoints:
- POST /api/agent/generate-itinerary  → generate full itinerary
- POST /api/agent/chat                → conversational trip modification
- POST /api/ml/predict-price          → price trend prediction
- POST /api/ml/allocate-budget        → budget allocation
- GET  /health                        → health check
"""

from fastapi import FastAPI
from agent.routes import router as agent_router

app = FastAPI(
    title="Team7 Agent & ML Service",
    description="Trip planner agent (DeepSeek LLM) + price/budget ML models",
    version="1.0.0"
)

@app.get("/health")
async def health_check():
    """Health check — used by Spring Boot and Docker Compose healthcheck"""
    return {"status": "healthy", "service": "agent-ml-service"}

app.include_router(agent_router)

# TODO: register ml routes (from ml.routes import router as ml_router)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
