import secrets
import string
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_db
from app.models import User, Device, UserDevice
from app.schemas import RegisterRequest, LoginRequest, AuthResponse
from app.auth import hash_password, verify_password, create_access_token, get_current_user

router = APIRouter(tags=["auth"])


def generate_parent_code() -> str:
    return "".join(secrets.choice(string.ascii_uppercase + string.digits) for _ in range(6))


@router.post("/auth/register", response_model=AuthResponse)
async def register(body: RegisterRequest, db: AsyncSession = Depends(get_db)):
    if body.role not in ("parent", "child"):
        raise HTTPException(status_code=400, detail="Role must be 'parent' or 'child'")
    if len(body.username) < 3:
        raise HTTPException(status_code=400, detail="Username must be at least 3 characters")
    if len(body.password) < 4:
        raise HTTPException(status_code=400, detail="Password must be at least 4 characters")

    existing = await db.execute(select(User).where(User.username == body.username))
    if existing.scalar_one_or_none():
        raise HTTPException(status_code=409, detail="Username already exists")

    parent_code = None
    if body.role == "parent":
        while True:
            candidate = generate_parent_code()
            dup = await db.execute(select(User).where(User.parent_code == candidate))
            if not dup.scalar_one_or_none():
                parent_code = candidate
                break

    user = User(
        username=body.username,
        password_hash=hash_password(body.password),
        role=body.role,
        parent_code=parent_code,
    )
    db.add(user)
    await db.commit()
    await db.refresh(user)

    if body.role == "child" and body.parent_code:
        result = await db.execute(select(User).where(
            User.parent_code == body.parent_code,
            User.role == "parent",
        ))
        parent = result.scalar_one_or_none()
        if parent:
            user.parent_id = parent.id
            await db.commit()

    token = create_access_token(str(user.id), user.username, user.role)
    return AuthResponse(
        token=token,
        user_id=str(user.id),
        username=user.username,
        role=user.role,
        parent_code=user.parent_code,
    )


@router.post("/auth/login", response_model=AuthResponse)
async def login(body: LoginRequest, db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(User).where(User.username == body.username))
    user = result.scalar_one_or_none()
    if user is None or not verify_password(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Invalid username or password")

    token = create_access_token(str(user.id), user.username, user.role)
    return AuthResponse(
        token=token,
        user_id=str(user.id),
        username=user.username,
        role=user.role,
        parent_code=user.parent_code,
    )


@router.get("/auth/me")
async def get_me(user: User = Depends(get_current_user)):
    return {
        "user_id": str(user.id),
        "username": user.username,
        "role": user.role,
        "parent_code": user.parent_code,
        "created_at": user.created_at.isoformat() if user.created_at else None,
    }


@router.post("/auth/change-password")
async def change_password(
    old_password: str,
    new_password: str,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if not verify_password(old_password, user.password_hash):
        raise HTTPException(status_code=401, detail="Incorrect password")
    if len(new_password) < 4:
        raise HTTPException(status_code=400, detail="New password must be at least 4 characters")
    user.password_hash = hash_password(new_password)
    await db.commit()
    return {"status": "ok"}
