import asyncio
import os

import httpx

TARGET = os.getenv("TRAFFIC_TARGET", "http://127.0.0.1:8081/work")


async def main() -> None:
    async with httpx.AsyncClient(timeout=5) as client:
        while True:
            try:
                response = await client.get(TARGET)
                print(f"demo request status={response.status_code}", flush=True)
            except httpx.HTTPError as error:
                print(f"demo request failed: {error}", flush=True)
            await asyncio.sleep(2)


if __name__ == "__main__":
    asyncio.run(main())
