"""Agent API routes - FastAPI router for agent endpoints"""
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Optional
from agent.orchestrator import extract_trip_requirements
from agent.orchestrator import extract_trip_requirements, generate_itinerary, modify_itinerary
from agent.task_manager import create_task, get_task, run_in_background

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

@router.get("/tasks/{task_id}")
async def get_task_status(task_id: str):
    """Poll this endpoint to check on a background task's progress/result."""
    task = get_task(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail=f"Unknown taskId: {task_id}")
    return task
