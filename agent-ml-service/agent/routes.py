"""Agent API routes — FastAPI router for agent endpoints"""

from fastapi import APIRouter

router = APIRouter(prefix="/api/agent", tags=["Agent"])

# TODO: POST /api/agent/generate-itinerary — TripRequest → Itinerary
# TODO: POST /api/agent/chat              — conversation message → agent reply
# TODO: GET  /api/agent/conversations/{id} — conversation history
