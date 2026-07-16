from datetime import date, datetime, timezone
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_db
from app.models import Device, DailyUsage, UsageSession
from app.schemas import SyncRequest, SyncResponse
from app.auth import get_device_from_api_key

router = APIRouter(tags=["usage"])


@router.post("/sync", response_model=SyncResponse)
async def sync_usage(
    body: SyncRequest,
    device: Device = Depends(get_device_from_api_key),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(DailyUsage).where(
            DailyUsage.device_id == device.id,
            DailyUsage.date == body.date,
        )
    )
    daily = result.scalar_one_or_none()

    if daily is None:
        daily = DailyUsage(
            device_id=device.id,
            date=body.date,
            total_seconds=body.total_seconds,
        )
        db.add(daily)
    else:
        daily.total_seconds = body.total_seconds
        daily.updated_at = datetime.now(timezone.utc)

    for sess in body.sessions:
        db.add(
            UsageSession(
                device_id=device.id,
                date=body.date,
                start_time=sess.start_time,
                end_time=sess.end_time,
                duration_seconds=sess.duration_seconds,
            )
        )

    await db.commit()
    return SyncResponse(
        status="ok",
        total_seconds=daily.total_seconds,
        daily_limit_minutes=device.daily_limit_minutes,
    )



