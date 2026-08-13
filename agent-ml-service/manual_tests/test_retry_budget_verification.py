import asyncio
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent.duffel_client import search_flights, book_flight_with_retry


async def main():
    for i in range(6):
        flights = await search_flights('PEK', 'SIN', '2026-09-15')
        if not flights:
            print(f'Run {i+1}: no flights found')
            continue
        result = await book_flight_with_retry(
            flights[0]['offerId'], 'Test Person', '1990-01-01',
            origin='PEK', destination='SIN', date='2026-09-15'
        )
        print(f"Run {i+1}: success={result['success']}, attempts={len(result.get('attempts', []))}")


if __name__ == "__main__":
    asyncio.run(main())
