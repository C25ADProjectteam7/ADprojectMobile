"""ML API routes — FastAPI router for price prediction and budget allocation"""

from fastapi import APIRouter

router = APIRouter(prefix="/api/ml", tags=["Machine Learning"])

# TODO: POST /api/ml/predict-price  — flight/hotel price trend prediction
# TODO: POST /api/ml/allocate-budget — intelligent budget allocation
# TODO: GET  /api/ml/model-info     — model metadata (version, training date, accuracy)
