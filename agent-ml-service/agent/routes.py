"""Agent API routes - FastAPI router for agent endpoints"""
from fastapi import APIRouter
from pydantic import BaseModel
from typing import Optional
from agent.orchestrator import extract_trip_requirements
from agent.orchestrator import extract_trip_requirements, generate_itinerary

router = APIRouter(prefix="/api/agent", tags=["Agent"])


class TripInputRequest(BaseModel):
    """Raw natural-language input from the user"""
    userInput: str


class TripRequirementsResponse(BaseModel):
    """Extraction result: proceed to itinerary generation when all fields are present,
    otherwise return clarifyingQuestion for the frontend to display"""
    destination: Optional[str] = None
    startDate: Optional[str] = None
    endDate: Optional[str] = None
    budgetTotal: Optional[float] = None
    preferences: list[str] = []
    missingFields: list[str] = []
    clarifyingQuestion: Optional[str] = None


@router.post("/extract-requirements", response_model=TripRequirementsResponse)
async def extract_requirements(request: TripInputRequest):
    """Backlog #4: extract trip requirements from free text.
    The Java backend calls this endpoint with the user's raw input text and
    receives structured fields back; if missingFields is non-empty, display
    clarifyingQuestion to the user and continue the conversation."""
    result = await extract_trip_requirements(request.userInput)
    return result

class GenerateItineraryRequest(BaseModel):
    """Full trip requirements needed to generate a complete itinerary"""
    originCity: str
    destination: str
    startDate: str
    endDate: str
    budgetTotal: float
    preferences: list[str] = []


@router.post("/generate-itinerary")
async def generate_itinerary_endpoint(request: GenerateItineraryRequest):
    """Backlog #6: generates a complete day-by-day itinerary using real
    flight, hotel, restaurant, and attraction data."""
    result = await generate_itinerary(request.model_dump())
    return result


class ModifyItineraryRequest(BaseModel):
    """Backlog #10: current itinerary + a natural-language change request"""
    currentItinerary: dict
    userRequest: str


@router.post("/modify-itinerary")
async def modify_itinerary_endpoint(request: ModifyItineraryRequest):
    """Backlog #10: modifies an existing itinerary based on conversational input."""
    from agent.orchestrator import modify_itinerary
    result = await modify_itinerary(request.currentItinerary, request.userRequest)
    return result


# TODO: GET  /api/agent/conversations/{id}