import asyncio
from agent.deepseek_client import chat_completion


async def main():
    reply = await chat_completion([
        {"role": "user", "content": "Introduce yourself in one sentence"}
    ])
    print("DeepSeek reply:", reply)


if __name__ == "__main__":
    asyncio.run(main())