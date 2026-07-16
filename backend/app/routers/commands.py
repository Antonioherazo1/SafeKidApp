from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload
from app.database import get_db
from app.models import User, Device, UserDevice, Command
from app.schemas import SendCommandRequest, PendingCommandsResponse, PendingCommand
from app.auth import get_current_user

router = APIRouter(tags=["commands"])


@router.post("/commands/send")
async def send_command(
    body: SendCommandRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if user.role != "parent":
        raise HTTPException(status_code=403, detail="Only parents can send commands")

    import uuid
    device_id = uuid.UUID(body.to_device_id)

    result = await db.execute(
        select(UserDevice).where(
            UserDevice.user_id == user.id,
            UserDevice.device_id == device_id,
        )
    )
    link = result.scalar_one_or_none()
    if link is None:
        raise HTTPException(status_code=404, detail="Child not linked to your account")

    if body.command_type not in ("block", "unblock", "start_tracking", "stop_tracking"):
        raise HTTPException(status_code=400, detail="Invalid command type")

    cmd = Command(
        from_user_id=user.id,
        to_device_id=device_id,
        command_type=body.command_type,
        status="pending",
    )
    db.add(cmd)
    await db.commit()

    return {"status": "queued", "command_id": cmd.id}


@router.get("/commands/pending", response_model=PendingCommandsResponse)
async def get_pending_commands(
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if user.role != "child":
        return PendingCommandsResponse(commands=[])

    result = await db.execute(
        select(UserDevice).where(UserDevice.user_id == user.id)
    )
    device_link = result.scalar_one_or_none()
    if device_link is None:
        return PendingCommandsResponse(commands=[])

    cmd_result = await db.execute(
        select(Command)
        .where(
            Command.to_device_id == device_link.device_id,
            Command.status == "pending",
        )
        .order_by(Command.created_at.asc())
    )
    cmds = cmd_result.scalars().all()

    return PendingCommandsResponse(commands=[
        PendingCommand(
            id=cmd.id,
            command_type=cmd.command_type,
            payload=cmd.payload,
            created_at=cmd.created_at,
        )
        for cmd in cmds
    ])


@router.post("/commands/{command_id}/delivered")
async def mark_delivered(
    command_id: int,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(Command).where(Command.id == command_id))
    cmd = result.scalar_one_or_none()
    if cmd is None:
        raise HTTPException(status_code=404, detail="Command not found")
    cmd.status = "delivered"
    await db.commit()
    return {"status": "ok"}


@router.post("/commands/{command_id}/executed")
async def mark_executed(
    command_id: int,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(Command).where(Command.id == command_id))
    cmd = result.scalar_one_or_none()
    if cmd is None:
        raise HTTPException(status_code=404, detail="Command not found")
    cmd.status = "executed"
    await db.commit()
    return {"status": "ok"}
