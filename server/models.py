"""PiPhotos storage-node models (Pydantic v2)."""

from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Enums
# ---------------------------------------------------------------------------

class UserRole(str, Enum):
    OWNER = "owner"
    MEMBER = "member"
    GUEST = "guest"


# ---------------------------------------------------------------------------
# Request models
# ---------------------------------------------------------------------------

class PairRequest(BaseModel):
    code: str = Field(..., min_length=6, max_length=6, pattern=r"^\d{6}$")
    device_name: str = Field(..., min_length=1, max_length=200)
    device_model: str = Field(..., min_length=1, max_length=200)


class CheckUploadRequest(BaseModel):
    hashes: list[str] = Field(..., max_length=1000)


# ---------------------------------------------------------------------------
# Response models
# ---------------------------------------------------------------------------

class ServerInfo(BaseModel):
    id: str
    name: str
    version: str
    protocol_version: int = 1
    pairing_open: bool


class PairResponse(BaseModel):
    token: str
    user_id: str
    device_id: str


class DiskInfo(BaseModel):
    total_bytes: int
    free_bytes: int
    reserved_bytes: int


class UserQuota(BaseModel):
    user_id: str
    quota_bytes: int
    used_bytes: int
    free_bytes: int
    file_count: int
    soft_quota_warning: Optional[str] = None
    organise_by_date: bool = False


class StatusResponse(BaseModel):
    disk: DiskInfo
    user: UserQuota


class UploadResult(BaseModel):
    sha256: str
    size: int
    path: str


class CheckUploadResponse(BaseModel):
    existing: list[str]
    partial: dict[str, int]


class FolderInfo(BaseModel):
    name: str
    item_count: int
    total_bytes: int


class DeviceFoldersResponse(BaseModel):
    device_id: str
    device_name: str
    folders: list[FolderInfo]


class ErrorResponse(BaseModel):
    error: str
    message: str


class OffsetMismatchDetail(BaseModel):
    error: str = "offset_mismatch"
    message: str
    offset: int


# ---------------------------------------------------------------------------
# Phase U1: Admin and User Management models
# ---------------------------------------------------------------------------

class AdminUserSummary(BaseModel):
    id: str
    name: str
    display_name: str
    slug: str
    role: str
    status: str
    quota_bytes: int
    used_bytes: int
    file_count: int
    devices_count: int
    last_seen_at: Optional[str] = None


class CreateUserRequest(BaseModel):
    display_name: str = Field(..., min_length=1, max_length=100)
    quota_bytes: int = Field(default=0, ge=0)


class CreateUserResponse(BaseModel):
    user: AdminUserSummary
    invite_code: str
    expires_at: str
    qr_payload: str


class UpdateUserRequest(BaseModel):
    display_name: Optional[str] = Field(default=None, min_length=1, max_length=100)
    quota_bytes: Optional[int] = Field(default=None, ge=0)
    status: Optional[str] = Field(default=None, pattern="^(active|disabled)$")
    role: Optional[str] = Field(default=None, pattern="^(owner|member)$")


class InviteResponse(BaseModel):
    invite_code: str
    expires_at: str
    qr_payload: str


class DeviceSummary(BaseModel):
    id: str
    name: str
    model: str
    slug: str
    created_at: str
    last_seen_at: Optional[str] = None


class OpenPairingResponse(BaseModel):
    code: str
    expires_in_seconds: int = 600


class UserOverview(BaseModel):
    id: str
    name: str
    role: str
    quota_bytes: int
    used_bytes: int
    file_count: int


class AdminOverviewResponse(BaseModel):
    disk: DiskInfo
    allocated_quota_total: int
    over_allocated: bool
    users: list[UserOverview]
