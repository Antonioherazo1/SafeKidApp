import uuid
from datetime import datetime, timezone
from sqlalchemy import (
    Column, String, Integer, BigInteger, Boolean, Date, DateTime,
    ForeignKey, Text, UniqueConstraint, Index,
)
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.orm import relationship
from app.database import Base


def utcnow():
    return datetime.now(timezone.utc)


class User(Base):
    __tablename__ = "safekid_users"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    username = Column(String(100), unique=True, nullable=False, index=True)
    password_hash = Column(String(255), nullable=False)
    role = Column(String(20), nullable=False, default="child")  # "parent" | "child"
    parent_code = Column(String(10), unique=True, nullable=True, index=True)
    parent_id = Column(UUID(as_uuid=True), ForeignKey("safekid_users.id", ondelete="SET NULL"), nullable=True)
    created_at = Column(DateTime(timezone=True), default=utcnow)

    linked_devices = relationship("UserDevice", back_populates="user", cascade="all, delete-orphan")
    sent_commands = relationship("Command", back_populates="sender", foreign_keys="Command.from_user_id")


class Device(Base):
    __tablename__ = "safekid_devices"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), nullable=False)
    api_key = Column(String(64), unique=True, nullable=False, index=True)
    daily_limit_minutes = Column(Integer, default=0)
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime(timezone=True), default=utcnow)
    updated_at = Column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)

    daily_usage = relationship("DailyUsage", back_populates="device", cascade="all, delete-orphan")
    sessions = relationship("UsageSession", back_populates="device", cascade="all, delete-orphan")
    user_links = relationship("UserDevice", back_populates="device", cascade="all, delete-orphan")
    commands = relationship("Command", back_populates="target_device", foreign_keys="Command.to_device_id")


class UserDevice(Base):
    __tablename__ = "safekid_user_devices"
    __table_args__ = (UniqueConstraint("user_id", "device_id"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(UUID(as_uuid=True), ForeignKey("safekid_users.id", ondelete="CASCADE"), nullable=False)
    device_id = Column(UUID(as_uuid=True), ForeignKey("safekid_devices.id", ondelete="CASCADE"), nullable=False)

    user = relationship("User", back_populates="linked_devices")
    device = relationship("Device", back_populates="user_links")


class Command(Base):
    __tablename__ = "safekid_commands"
    __table_args__ = (Index("idx_commands_device_status", "to_device_id", "status"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    from_user_id = Column(UUID(as_uuid=True), ForeignKey("safekid_users.id", ondelete="CASCADE"), nullable=False)
    to_device_id = Column(UUID(as_uuid=True), ForeignKey("safekid_devices.id", ondelete="CASCADE"), nullable=False)
    command_type = Column(String(50), nullable=False)
    payload = Column(JSONB, nullable=True)
    status = Column(String(20), nullable=False, default="pending")  # pending | delivered | executed
    created_at = Column(DateTime(timezone=True), default=utcnow)

    sender = relationship("User", back_populates="sent_commands", foreign_keys=[from_user_id])
    target_device = relationship("Device", back_populates="commands", foreign_keys=[to_device_id])


class DailyUsage(Base):
    __tablename__ = "safekid_daily_usage"
    __table_args__ = (UniqueConstraint("device_id", "date"),)

    id = Column(Integer, primary_key=True, autoincrement=True)
    device_id = Column(UUID(as_uuid=True), ForeignKey("safekid_devices.id", ondelete="CASCADE"), nullable=False)
    date = Column(Date, nullable=False)
    total_seconds = Column(BigInteger, default=0)
    created_at = Column(DateTime(timezone=True), default=utcnow)
    updated_at = Column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)

    device = relationship("Device", back_populates="daily_usage")


class UsageSession(Base):
    __tablename__ = "safekid_usage_sessions"

    id = Column(Integer, primary_key=True, autoincrement=True)
    device_id = Column(UUID(as_uuid=True), ForeignKey("safekid_devices.id", ondelete="CASCADE"), nullable=False)
    date = Column(Date, nullable=False)
    start_time = Column(DateTime(timezone=True), nullable=False)
    end_time = Column(DateTime(timezone=True), nullable=True)
    duration_seconds = Column(BigInteger, default=0)
    created_at = Column(DateTime(timezone=True), default=utcnow)

    device = relationship("Device", back_populates="sessions")
