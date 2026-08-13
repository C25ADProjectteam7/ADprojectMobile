import asyncio
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent.deepseek_client import chat_completion


async def main():
    reply = await chat_completion([
        {"role": "user", "content": "Introduce yourself in one sentence"}
    ])
    print("DeepSeek reply:", reply)


if __name__ == "__main__":
    asyncio.run(main())
