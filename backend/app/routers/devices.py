import uuid
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload
from app.database import get_db
from app.models import Device, UserDevice, User
from app.schemas import RegisterDeviceRequest, RegisterDeviceResponse
from app.auth import generate_api_key, get_device_from_api_key, get_current_user

router = APIRouter(tags=["devices"])


@router.post("/device/register", response_model=RegisterDeviceResponse)
async def register_device(
    body: RegisterDeviceRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if user.role != "child":
        raise HTTPException(status_code=403, detail="Only child accounts can register devices")

    existing = await db.execute(
        select(UserDevice).where(UserDevice.user_id == user.id)
    )
    if existing.scalar_one_or_none():
        raise HTTPException(status_code=409, detail="Child user already has a registered device")

    device_id = uuid.uuid4()
    api_key = generate_api_key(str(device_id))

    device = Device(
        id=device_id,
        name=body.name,
        api_key=api_key,
    )
    db.add(device)
    await db.flush()

    link = UserDevice(user_id=user.id, device_id=device.id)
    db.add(link)
    await db.commit()
    await db.refresh(device)

    return RegisterDeviceResponse(
        device_id=str(device.id),
        api_key=api_key,
        name=device.name,
    )


@router.get("/device/info")
async def get_device_info(device: Device = Depends(get_device_from_api_key)):
    return {
        "device_id": str(device.id),
        "name": device.name,
        "daily_limit_minutes": device.daily_limit_minutes,
        "is_active": device.is_active,
        "created_at": device.created_at.isoformat() if device.created_at else None,
    }


@router.put("/device/limit")
async def set_daily_limit(
    limit_minutes: int,
    device: Device = Depends(get_device_from_api_key),
    db: AsyncSession = Depends(get_db),
):
    device.daily_limit_minutes = limit_minutes
    await db.commit()
    return {"status": "ok", "daily_limit_minutes": limit_minutes}
