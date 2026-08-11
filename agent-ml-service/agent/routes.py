"""Agent API routes - FastAPI router for agent endpoints"""
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from typing import Optional
from agent.orchestrator import (
    extract_trip_requirements, generate_itinerary, modify_itinerary, book_full_trip,
    summarize_conversation,
)
from agent.task_manager import create_task, get_task, run_in_background

router = APIRouter(prefix="/api/agent", tags=["Agent"])


class ConversationTurn(BaseModel):
    """One prior turn of a trip's agent-chat conversation, oldest first."""
    role: str  # "user" or "assistant"
    content: str


class TripInputRequest(BaseModel):
    """Raw natural-language input from the user"""
    userInput: str
    conversationHistory: list[ConversationTurn] = Field(default_factory=list)
    conversationSummary: Optional[str] = None


class TripRequirementsResponse(BaseModel):
    """Extraction result: proceed to itinerary generation when all fields are present,
    otherwise return clarifyingQuestion for the frontend to display"""
    # Keep this contract aligned with TripService.agentChat(): all required
    # itinerary-generation inputs extracted by the LLM must survive FastAPI's
    # response-model filtering.
    originCity: Optional[str] = None
    destination: Optional[str] = None
    startDate: Optional[str] = None
    endDate: Optional[str] = None
    budgetTotal: Optional[float] = None
    preferences: list[str] = Field(default_factory=list)
    maxHotelCommuteMinutes: Optional[int] = None
    missingFields: list[str] = Field(default_factory=list)
    clarifyingQuestion: Optional[str] = None


@router.post("/extract-requirements", response_model=TripRequirementsResponse)
async def extract_requirements(request: TripInputRequest):
    """Backlog #4: extract trip requirements from free text.
    The Java backend calls this endpoint with the user's raw input text and
    receives structured fields back; if missingFields is non-empty, display
    clarifyingQuestion to the user and continue the conversation."""
    history = [turn.model_dump() for turn in request.conversationHistory]
    result = await extract_trip_requirements(request.userInput, history, request.conversationSummary)
    return result


class SummarizeConversationRequest(BaseModel):
    """Compresses conversation turns that have aged out of the recent verbatim
    window into a short running summary - see orchestrator.summarize_conversation()."""
    previousSummary: Optional[str] = None
    turns: list[ConversationTurn]


@router.post("/summarize-conversation")
async def summarize_conversation_endpoint(request: SummarizeConversationRequest):
    """Sync, not a background task - summarizing a handful of short chat
    turns is fast, unlike itinerary generation/modification/booking."""
    summary = await summarize_conversation(
        request.previousSummary, [turn.model_dump() for turn in request.turns]
    )
    return {"summary": summary}

class GenerateItineraryRequest(BaseModel):
    """Full trip requirements needed to generate a complete itinerary"""
    originCity: str
    destination: str
    startDate: str
    endDate: str
    budgetTotal: float
    preferences: list[str] = []
    debug: bool = False


@router.post("/generate-itinerary")
async def generate_itinerary_endpoint(request: GenerateItineraryRequest):
    """Backlog #6: starts itinerary generation as a background task.
    Returns immediately with a taskId - poll GET /tasks/{taskId} for the result."""
    task_id = create_task()
    trip_requirements = request.model_dump(exclude={"debug"})
    run_in_background(task_id, generate_itinerary(trip_requirements, debug=request.debug))
    return {"taskId": task_id}


class ModifyItineraryRequest(BaseModel):
    """Backlog #10: current itinerary + a natural-language change request"""
    currentItinerary: dict
    userRequest: str
    debug: bool = False


@router.post("/modify-itinerary")
async def modify_itinerary_endpoint(request: ModifyItineraryRequest):
    """Backlog #10: starts itinerary modification as a background task."""
    task_id = create_task()
    run_in_background(
        task_id,
        modify_itinerary(request.currentItinerary, request.userRequest, debug=request.debug)
    )
    return {"taskId": task_id}

class BookTripRequest(BaseModel):
    """Backlog #9: books flight + hotel together for a previously generated/
    modified itinerary. origin/destination/date and latitude/longitude/
    checkIn/checkOut/budget are optional but should be supplied whenever
    available - they let the booking flow proactively re-search and recover
    if the offerIds in the itinerary have gone stale or expired."""
    itinerary: dict
    flightOfferId: str
    hotelOfferId: str
    passengerName: str
    passengerDob: str
    email: str
    origin: Optional[str] = None
    destination: Optional[str] = None
    date: Optional[str] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    checkIn: Optional[str] = None
    checkOut: Optional[str] = None
    budget: Optional[float] = None
    guestNationality: str = "SG"


@router.post("/book-trip")
async def book_trip_endpoint(request: BookTripRequest):
    """Backlog #9: starts flight+hotel booking as a background task.
    Returns immediately with a taskId - poll GET /tasks/{taskId} for the result."""
    task_id = create_task()
    run_in_background(
        task_id,
        book_full_trip(
            request.itinerary, request.flightOfferId, request.hotelOfferId,
            request.passengerName, request.passengerDob, request.email,
            origin=request.origin, destination=request.destination, date_str=request.date,
            latitude=request.latitude, longitude=request.longitude,
            check_in=request.checkIn, check_out=request.checkOut,
            budget=request.budget, guest_nationality=request.guestNationality,
        )
    )
    return {"taskId": task_id}


@router.get("/tasks/{task_id}")
async def get_task_status(task_id: str):
    """Poll this endpoint to check on a background task's progress/result."""
    task = get_task(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail=f"Unknown taskId: {task_id}")
    return task
