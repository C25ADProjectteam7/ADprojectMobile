import asyncio
import httpx


async def poll_task(client, task_id, label, max_polls=60, interval=3):
    for i in range(max_polls):
        await asyncio.sleep(interval)
        response = await client.get(f"http://localhost:8000/api/agent/tasks/{task_id}")
        status = response.json()
        print(f"[{label}] Poll {i+1} (t={(i+1)*interval}s): status={status['status']}")
        if status["status"] in ("completed", "failed"):
            return status
    print(f"[{label}] Still not done after {max_polls * interval} seconds")
    return None


async def main():
    async with httpx.AsyncClient(timeout=180.0) as client:
        # Step 1: generate an itinerary first
        gen_response = await client.post(
            "http://localhost:8000/api/agent/generate-itinerary",
            json={
                "originCity": "Beijing", "destination": "Singapore",
                "startDate": "2026-08-10", "endDate": "2026-08-12",
                "budgetTotal": 2000, "preferences": ["seafood"]
            }
        )
        gen_task_id = gen_response.json()["taskId"]
        print("Generate taskId:", gen_task_id)

        gen_status = await poll_task(client, gen_task_id, "generate")
        if gen_status is None or gen_status["status"] != "completed":
            print("Generation didn't complete, stopping.")
            return

        original_itinerary = gen_status["result"]
        print("\nOriginal day1 hotel:", original_itinerary["day1"]["hotel"]["name"])

        # Step 2: modify it
        mod_response = await client.post(
            "http://localhost:8000/api/agent/modify-itinerary",
            json={
                "currentItinerary": original_itinerary,
                "userRequest": "Can you find me a nicer, more expensive hotel instead?"
            }
        )
        mod_task_id = mod_response.json()["taskId"]
        print("\nModify taskId:", mod_task_id)

        mod_status = await poll_task(client, mod_task_id, "modify")
        if mod_status is None or mod_status["status"] != "completed":
            print("Modification didn't complete.")
            return

        updated_itinerary = mod_status["result"]
        print("\nUpdated day1 hotel:", updated_itinerary["day1"]["hotel"]["name"])


if __name__ == "__main__":
    asyncio.run(main())