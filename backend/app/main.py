from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.database import init_db
from app.routers import auth, devices, usage, stats, commands, parent


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    yield


app = FastAPI(title="SafeKid API", version="2.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth.router, prefix="/api/safekid")
app.include_router(devices.router, prefix="/api/safekid")
app.include_router(usage.router, prefix="/api/safekid")
app.include_router(stats.router, prefix="/api/safekid")
app.include_router(commands.router, prefix="/api/safekid")
app.include_router(parent.router, prefix="/api/safekid")


@app.get("/health")
@app.get("/api/safekid/health")
async def health():
    return {"status": "ok"}
