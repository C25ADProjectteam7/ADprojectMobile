"""
Agent orchestrator — itinerary generation and modification logic
================================================================

generate_itinerary flow:
1. Receive TripRequest (destination, dates, budget, preferences)
2. Build system prompt (agent role + behavioral constraints)
3. Call ML budget allocator → get spending breakdown per category
4. Guide agent step by step: search flights → hotels → restaurants → assemble
5. Agent uses tool calling to fetch real data
6. Parse results into structured itinerary JSON
7. Return to Spring Boot backend

modify_itinerary flow:
1. Load conversation history from DB
2. User sends change request → agent analyzes delta → re-searches → updates
"""

# TODO: generate_itinerary() — full trip generation
# TODO: modify_itinerary() — conversational modification
# TODO: _build_system_prompt() — agent role description
# TODO: _parse_itinerary_response() — parse agent output to structured JSON
