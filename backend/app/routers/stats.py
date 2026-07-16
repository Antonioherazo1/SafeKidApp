from datetime import date, timedelta
from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_db
from app.models import Device, DailyUsage
from app.schemas import StatsResponse, DailyStats, HistoryEntry
from app.auth import get_device_from_api_key

router = APIRouter(tags=["stats"])


@router.get("/stats", response_model=StatsResponse)
async def get_stats(
    days: int = Query(default=7, ge=1, le=90),
    device: Device = Depends(get_device_from_api_key),
    db: AsyncSession = Depends(get_db),
):
    today = date.today()

    result = await db.execute(
        select(DailyUsage).where(
            DailyUsage.device_id == device.id,
            DailyUsage.date == today,
        )
    )
    today_row = result.scalar_one_or_none()

    today_stats = DailyStats(
        date=today,
        total_seconds=today_row.total_seconds if today_row else 0,
        limit_minutes=device.daily_limit_minutes,
    )

    start_date = today - timedelta(days=days - 1)
    result = await db.execute(
        select(DailyUsage)
        .where(
            DailyUsage.device_id == device.id,
            DailyUsage.date >= start_date,
            DailyUsage.date <= today,
        )
        .order_by(DailyUsage.date.desc())
    )
    rows = result.scalars().all()

    history_map = {row.date.isoformat(): row.total_seconds for row in rows}

    history = []
    for i in range(days):
        dt = today - timedelta(days=i)
        history.append(
            HistoryEntry(
                date=dt,
                total_seconds=history_map.get(dt.isoformat(), 0),
                limit_minutes=device.daily_limit_minutes,
            )
        )

    return StatsResponse(today=today_stats, history=history)
