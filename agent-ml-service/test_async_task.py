import asyncio
import httpx


async def main():
    async with httpx.AsyncClient(timeout=180.0) as client:
        response = await client.post(
            "http://localhost:8000/api/agent/generate-itinerary",
            json={
                "originCity": "Beijing", "destination": "Singapore",
                "startDate": "2026-08-10", "endDate": "2026-08-12",
                "budgetTotal": 2000, "preferences": ["seafood"]
            }
        )
        task_id = response.json()["taskId"]
        print("Got taskId:", task_id)

        for i in range(60):
            await asyncio.sleep(3)
            status_response = await client.get(f"http://localhost:8000/api/agent/tasks/{task_id}")
            status = status_response.json()
            print(f"Poll {i+1} (t={(i+1)*3}s): status={status['status']}")
            if status["status"] in ("completed", "failed"):
                print("Final result:")
                print(status)
                break
        else:
            print("Still not done after 180 seconds - something may actually be stuck")


if __name__ == "__main__":
    asyncio.run(main())