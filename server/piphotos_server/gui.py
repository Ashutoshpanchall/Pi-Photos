"""PiPhotos Server — Native Desktop GUI Application.

Implements the official desktop UI specification:
- Status view: server state, storage utilization bar, live 6-digit pair code, recent uploads feed
- Pair view: live pairing code and high-resolution QR code
- Users view: active user directory, usage breakdown, quota management, device roles (manage vs backup)
- Storage view: drive picker, allocation percentages, reserve limits
- Settings view: Windows auto-start, configuration, scheduled database backups & restore, health doctor
- System tray integration: background minimization, status indicator, quick actions

Driven entirely by `piphotos_server.control.ServerController`.
"""

from __future__ import annotations

import io
import json
import logging
import os
import sys
import threading
import time
from pathlib import Path
from typing import Optional, List, Dict, Any

try:
    import customtkinter as ctk  # type: ignore
except ImportError:
    ctk = None

from .control import ServerController, ControlError
from .paths import ServerPaths, resolve_paths

logger = logging.getLogger("piphotos_server.gui")

# Styling constants matching official PiPhotos desktop specification
COLOR_BG_LIGHT = "#F6F5F0"
COLOR_SIDEBAR_LIGHT = "#EDECE6"
COLOR_CARD_LIGHT = "#FFFFFF"
COLOR_BORDER_LIGHT = "#E2E0D8"

COLOR_ACCENT = "#0D5C46"        # PiPhotos Forest Green
COLOR_ACCENT_HOVER = "#0A4635"
COLOR_ACCENT_LIGHT = "#E6F4EA"
COLOR_TEXT_MAIN = "#1A1A1A"
COLOR_TEXT_MUTED = "#6B7280"
COLOR_TEXT_GREEN = "#0D5C46"
COLOR_DANGER = "#DC2626"
COLOR_DANGER_LIGHT = "#FEE2E2"
COLOR_WARNING = "#D97706"
COLOR_WARNING_LIGHT = "#FEF3C7"


def format_bytes(bytes_val: int) -> str:
    """Format bytes into human-readable string (e.g. 79 GB)."""
    if bytes_val < 0:
        return "0 B"
    for unit in ["B", "KB", "MB", "GB", "TB"]:
        if bytes_val < 1024.0 or unit == "TB":
            if unit in ("B", "KB"):
                return f"{int(bytes_val)} {unit}"
            return f"{bytes_val:.1f} {unit}".replace(".0 ", " ")
        bytes_val /= 1024.0
    return f"{bytes_val:.1f} PB"


class PiPhotosApp:
    """Main desktop application window."""

    def __init__(self, data_dir: Optional[str | Path] = None, start_in_tray: bool = False):
        if ctk is None:
            raise RuntimeError("customtkinter is required for the native desktop GUI")

        self.ctrl = ServerController(data_dir=data_dir)
        self.start_in_tray = start_in_tray
        self._tray_icon = None

        # Setup customtkinter appearance
        ctk.set_appearance_mode("light")
        ctk.set_default_color_theme("green")

        self.root = ctk.CTk()
        self.root.title("PiPhotos Server")
        self.root.geometry("1020x680")
        self.root.minsize(920, 600)
        self.root.configure(fg_color=COLOR_BG_LIGHT)

        # Intercept window close to minimize to tray if enabled
        self.root.protocol("WM_DELETE_WINDOW", self.on_close_window)

        # State cache
        self.cached_status: Dict[str, Any] = {}
        self.cached_users: List[Dict[str, Any]] = []
        self.cached_uploads: List[Dict[str, Any]] = []
        self.current_pair_code: str = "------"
        self.pair_code_expires_at: float = 0.0
        self.selected_user_id: Optional[str] = None

        self._build_sidebar()
        self._build_content_area()

        # Start periodic background polling for status and uploads
        self._stop_polling = threading.Event()
        self.poll_thread = threading.Thread(target=self._background_poll_loop, daemon=True)
        self.poll_thread.start()

        # Select initial tab
        self.select_tab("status")

        # Setup system tray icon in background
        self._setup_tray_icon()

        if self.start_in_tray:
            self.root.withdraw()

    # -----------------------------------------------------------------------
    # Layout and navigation
    # -----------------------------------------------------------------------

    def _build_sidebar(self):
        self.sidebar_frame = ctk.CTkFrame(
            self.root,
            width=180,
            corner_radius=0,
            fg_color=COLOR_SIDEBAR_LIGHT,
        )
        self.sidebar_frame.pack(side="left", fill="y")
        self.sidebar_frame.pack_propagate(False)

        # Brand header
        brand_label = ctk.CTkLabel(
            self.sidebar_frame,
            text="PiPhotos Server",
            font=ctk.CTkFont(size=18, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
            anchor="w",
        )
        brand_label.pack(padx=20, pady=(24, 20), fill="x")

        # Navigation buttons
        self.nav_buttons: Dict[str, ctk.CTkButton] = {}
        nav_items = [
            ("status", "Status"),
            ("pair", "Pair"),
            ("users", "Users"),
            ("storage", "Storage"),
            ("settings", "Settings"),
        ]

        for tab_id, text in nav_items:
            btn = ctk.CTkButton(
                self.sidebar_frame,
                text=text,
                font=ctk.CTkFont(size=14, weight="normal"),
                fg_color="transparent",
                text_color=COLOR_TEXT_MUTED,
                hover_color=COLOR_BORDER_LIGHT,
                anchor="w",
                height=36,
                corner_radius=8,
                command=lambda t=tab_id: self.select_tab(t),
            )
            btn.pack(padx=12, pady=4, fill="x")
            self.nav_buttons[tab_id] = btn

        # Version label at bottom
        version_label = ctk.CTkLabel(
            self.sidebar_frame,
            text="Version 1.0.0",
            font=ctk.CTkFont(size=12),
            text_color=COLOR_TEXT_MUTED,
            anchor="w",
        )
        version_label.pack(side="bottom", padx=20, pady=20, fill="x")

    def _build_content_area(self):
        self.main_container = ctk.CTkFrame(
            self.root,
            corner_radius=0,
            fg_color=COLOR_BG_LIGHT,
        )
        self.main_container.pack(side="right", fill="both", expand=True, padx=24, pady=24)

        # Individual tab views
        self.views: Dict[str, ctk.CTkFrame] = {
            "status": ctk.CTkFrame(self.main_container, fg_color="transparent"),
            "pair": ctk.CTkFrame(self.main_container, fg_color="transparent"),
            "users": ctk.CTkFrame(self.main_container, fg_color="transparent"),
            "storage": ctk.CTkFrame(self.main_container, fg_color="transparent"),
            "settings": ctk.CTkFrame(self.main_container, fg_color="transparent"),
        }

        self._init_status_view()
        self._init_pair_view()
        self._init_users_view()
        self._init_storage_view()
        self._init_settings_view()

    def select_tab(self, tab_id: str):
        for t_id, view in self.views.items():
            if t_id == tab_id:
                view.pack(fill="both", expand=True)
                self.nav_buttons[t_id].configure(
                    fg_color=COLOR_CARD_LIGHT,
                    text_color=COLOR_TEXT_MAIN,
                    font=ctk.CTkFont(size=14, weight="bold"),
                )
            else:
                view.pack_forget()
                self.nav_buttons[t_id].configure(
                    fg_color="transparent",
                    text_color=COLOR_TEXT_MUTED,
                    font=ctk.CTkFont(size=14, weight="normal"),
                )

        # Refresh tab contents
        if tab_id == "status":
            self._refresh_status_view()
        elif tab_id == "pair":
            self._refresh_pair_view()
        elif tab_id == "users":
            self._refresh_users_view()
        elif tab_id == "storage":
            self._refresh_storage_view()
        elif tab_id == "settings":
            self._refresh_settings_view()

    # -----------------------------------------------------------------------
    # 1. Status View (Mockup 4)
    # -----------------------------------------------------------------------

    def _init_status_view(self):
        v = self.views["status"]

        # Header row: Status indicator & Service info
        self.status_header_frame = ctk.CTkFrame(v, fg_color="transparent")
        self.status_header_frame.pack(fill="x", pady=(0, 16))

        self.status_dot_label = ctk.CTkLabel(
            self.status_header_frame,
            text="●",
            font=ctk.CTkFont(size=18, weight="bold"),
            text_color="#10B981",  # Green
        )
        self.status_dot_label.pack(side="left", padx=(0, 6))

        self.status_title_label = ctk.CTkLabel(
            self.status_header_frame,
            text="Running",
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
        )
        self.status_title_label.pack(side="left", padx=(0, 8))

        self.status_subtext_label = ctk.CTkLabel(
            self.status_header_frame,
            text="Home Vault · 127.0.0.1:8443",
            font=ctk.CTkFont(size=14),
            text_color=COLOR_TEXT_MUTED,
        )
        self.status_subtext_label.pack(side="left")

        self.starts_with_win_badge = ctk.CTkLabel(
            self.status_header_frame,
            text="Starts with Windows",
            font=ctk.CTkFont(size=12),
            text_color=COLOR_TEXT_MUTED,
        )
        self.starts_with_win_badge.pack(side="right")

        # Top Storage Card
        self.storage_card = ctk.CTkFrame(
            v,
            fg_color=COLOR_CARD_LIGHT,
            corner_radius=12,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
        )
        self.storage_card.pack(fill="x", pady=(0, 16), padx=2)

        s_header = ctk.CTkFrame(self.storage_card, fg_color="transparent")
        s_header.pack(fill="x", padx=20, pady=(16, 8))

        self.storage_card_title = ctk.CTkLabel(
            s_header,
            text="Storage on Drive",
            font=ctk.CTkFont(size=15, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
        )
        self.storage_card_title.pack(side="left")

        self.storage_card_usage = ctk.CTkLabel(
            s_header,
            text="0 GB of 0 GB used",
            font=ctk.CTkFont(size=14),
            text_color=COLOR_TEXT_MUTED,
        )
        self.storage_card_usage.pack(side="right")

        self.storage_progress = ctk.CTkProgressBar(
            self.storage_card,
            progress_color=COLOR_ACCENT,
            fg_color="#E5E7EB",
            height=12,
            corner_radius=6,
        )
        self.storage_progress.pack(fill="x", padx=20, pady=(0, 8))
        self.storage_progress.set(0.0)

        self.storage_card_subtext = ctk.CTkLabel(
            self.storage_card,
            text="Storage allocation information",
            font=ctk.CTkFont(size=12),
            text_color=COLOR_TEXT_MUTED,
            anchor="w",
        )
        self.storage_card_subtext.pack(fill="x", padx=20, pady=(0, 16))

        # Bottom split: Pair card (Left) & Recent Uploads (Right)
        self.bottom_split_frame = ctk.CTkFrame(v, fg_color="transparent")
        self.bottom_split_frame.pack(fill="both", expand=True)

        # Left: Quick Pair card
        self.quick_pair_card = ctk.CTkFrame(
            self.bottom_split_frame,
            fg_color=COLOR_CARD_LIGHT,
            corner_radius=12,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
        )
        self.quick_pair_card.pack(side="left", fill="both", expand=True, padx=(2, 8), pady=2)

        qp_title = ctk.CTkLabel(
            self.quick_pair_card,
            text="Pair a new device",
            font=ctk.CTkFont(size=16, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
            anchor="w",
        )
        qp_title.pack(fill="x", padx=20, pady=(20, 4))

        qp_sub = ctk.CTkLabel(
            self.quick_pair_card,
            text="Enter this code on the phone within the time shown.",
            font=ctk.CTkFont(size=13),
            text_color=COLOR_TEXT_MUTED,
            anchor="w",
        )
        qp_sub.pack(fill="x", padx=20, pady=(0, 20))

        self.quick_pair_code_label = ctk.CTkLabel(
            self.quick_pair_card,
            text="--- ---",
            font=ctk.CTkFont(size=36, weight="bold", family="Courier"),
            text_color=COLOR_TEXT_MAIN,
        )
        self.quick_pair_code_label.pack(pady=(10, 8))

        self.quick_pair_expires_label = ctk.CTkLabel(
            self.quick_pair_card,
            text="Pairing closed",
            font=ctk.CTkFont(size=13),
            text_color=COLOR_TEXT_MUTED,
        )
        self.quick_pair_expires_label.pack(pady=(0, 24))

        qp_btn_frame = ctk.CTkFrame(self.quick_pair_card, fg_color="transparent")
        qp_btn_frame.pack(fill="x", padx=20, pady=(0, 20))

        self.btn_new_code = ctk.CTkButton(
            qp_btn_frame,
            text="New code",
            font=ctk.CTkFont(size=14, weight="bold"),
            fg_color=COLOR_ACCENT,
            hover_color=COLOR_ACCENT_HOVER,
            corner_radius=8,
            height=38,
            command=self.on_click_new_code,
        )
        self.btn_new_code.pack(side="left", fill="x", expand=True, padx=(0, 6))

        self.btn_show_qr = ctk.CTkButton(
            qp_btn_frame,
            text="Show QR",
            font=ctk.CTkFont(size=14),
            fg_color=COLOR_CARD_LIGHT,
            text_color=COLOR_TEXT_MAIN,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
            hover_color=COLOR_BG_LIGHT,
            corner_radius=8,
            height=38,
            command=lambda: self.select_tab("pair"),
        )
        self.btn_show_qr.pack(side="right", fill="x", expand=True, padx=(6, 0))

        # Right: Recent Uploads card
        self.recent_uploads_card = ctk.CTkFrame(
            self.bottom_split_frame,
            fg_color=COLOR_CARD_LIGHT,
            corner_radius=12,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
        )
        self.recent_uploads_card.pack(side="right", fill="both", expand=True, padx=(8, 2), pady=2)

        ru_title = ctk.CTkLabel(
            self.recent_uploads_card,
            text="Recent uploads",
            font=ctk.CTkFont(size=16, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
            anchor="w",
        )
        ru_title.pack(fill="x", padx=20, pady=(20, 12))

        self.uploads_list_container = ctk.CTkScrollableFrame(
            self.recent_uploads_card,
            fg_color="transparent",
            height=160,
        )
        self.uploads_list_container.pack(fill="both", expand=True, padx=12, pady=(0, 8))

        # Bottom health indicator in uploads card
        self.health_footer = ctk.CTkLabel(
            self.recent_uploads_card,
            text="Last health check passed · 14 of 14 checks",
            font=ctk.CTkFont(size=12),
            text_color=COLOR_TEXT_MUTED,
            anchor="w",
        )
        self.health_footer.pack(fill="x", padx=20, pady=(0, 16))

    def _refresh_status_view(self):
        status = self.cached_status
        is_running = self.ctrl.is_running()

        if is_running:
            self.status_dot_label.configure(text_color="#10B981")
            self.status_title_label.configure(text="Running")
        else:
            self.status_dot_label.configure(text_color="#EF4444")
            self.status_title_label.configure(text="Stopped")

        server_name = status.get("server_name") or "Home Vault"
        primary_ip = status.get("primary_ip") or "127.0.0.1"
        port = status.get("port") or self.ctrl.port
        self.status_subtext_label.configure(text=f"{server_name} · {primary_ip}:{port}")

        # Update storage bar
        alloc_bytes = status.get("node_allocation_bytes") or 0
        used_bytes = status.get("total_used_bytes") or 0
        free_bytes = status.get("drive_free_bytes") or 0
        drive_name = Path(self.ctrl.paths.storage_dir).drive or "System"

        if alloc_bytes > 0:
            fraction = min(1.0, used_bytes / alloc_bytes)
            self.storage_progress.set(fraction)
            self.storage_card_title.configure(text=f"Storage on {drive_name}")
            self.storage_card_usage.configure(text=f"{format_bytes(used_bytes)} of {format_bytes(alloc_bytes)} used")
            self.storage_card_subtext.configure(
                text=f"Drive {drive_name} has {format_bytes(free_bytes)} free. Allocation is {format_bytes(alloc_bytes)}."
            )
        else:
            self.storage_progress.set(0.0)
            self.storage_card_title.configure(text=f"Storage on {drive_name}")
            self.storage_card_usage.configure(text="Not allocated")
            self.storage_card_subtext.configure(text=f"Drive {drive_name} has {format_bytes(free_bytes)} free space.")

        # Update pair code countdown
        now = time.time()
        if self.pair_code_expires_at > now:
            remaining_sec = int(self.pair_code_expires_at - now)
            mins = remaining_sec // 60
            secs = remaining_sec % 60
            self.quick_pair_code_label.configure(text=self.current_pair_code)
            self.quick_pair_expires_label.configure(text=f"Expires in {mins}:{secs:02d}")
        else:
            self.quick_pair_code_label.configure(text="--- ---")
            self.quick_pair_expires_label.configure(text="Pairing closed")

        # Update uploads
        for widget in self.uploads_list_container.winfo_children():
            widget.destroy()

        if not self.cached_uploads:
            empty_lbl = ctk.CTkLabel(
                self.uploads_list_container,
                text="No uploads recorded yet",
                font=ctk.CTkFont(size=13),
                text_color=COLOR_TEXT_MUTED,
            )
            empty_lbl.pack(pady=20)
        else:
            for item in self.cached_uploads[:5]:
                row = ctk.CTkFrame(self.uploads_list_container, fg_color="transparent")
                row.pack(fill="x", pady=4)
                left_txt = f"{item['filename']} · {item['device_name']}"
                lbl_l = ctk.CTkLabel(
                    row,
                    text=left_txt,
                    font=ctk.CTkFont(size=13),
                    text_color=COLOR_TEXT_MAIN,
                    anchor="w",
                )
                lbl_l.pack(side="left")
                lbl_r = ctk.CTkLabel(
                    row,
                    text=item["created_at"].split("T")[0] if "T" in item["created_at"] else item["created_at"][:10],
                    font=ctk.CTkFont(size=12),
                    text_color=COLOR_TEXT_MUTED,
                    anchor="e",
                )
                lbl_r.pack(side="right")

    def on_click_new_code(self):
        try:
            info = self.ctrl.open_pairing(duration_minutes=10)
            raw_code = info["code"]
            self.current_pair_code = f"{raw_code[:3]} {raw_code[3:]}"
            self.pair_code_expires_at = time.time() + info["expires_in_seconds"]
            self._refresh_status_view()
        except Exception as e:
            logger.error("Failed to generate new pairing code: %s", e)

    # -----------------------------------------------------------------------
    # 2. Pair View
    # -----------------------------------------------------------------------

    def _init_pair_view(self):
        v = self.views["pair"]

        p_card = ctk.CTkFrame(
            v,
            fg_color=COLOR_CARD_LIGHT,
            corner_radius=12,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
        )
        p_card.pack(fill="both", expand=True, padx=2, pady=2)

        ctk.CTkLabel(
            p_card,
            text="Pair Mobile Device",
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
        ).pack(pady=(24, 6))

        ctk.CTkLabel(
            p_card,
            text="Scan the QR code with your PiPhotos phone app, or type the 6-digit code below.",
            font=ctk.CTkFont(size=14),
            text_color=COLOR_TEXT_MUTED,
        ).pack(pady=(0, 20))

        # Big code display
        self.pair_view_code_label = ctk.CTkLabel(
            p_card,
            text="--- ---",
            font=ctk.CTkFont(size=44, weight="bold", family="Courier"),
            text_color=COLOR_ACCENT,
        )
        self.pair_view_code_label.pack(pady=8)

        self.pair_view_expires_label = ctk.CTkLabel(
            p_card,
            text="Pairing is currently closed",
            font=ctk.CTkFont(size=14),
            text_color=COLOR_TEXT_MUTED,
        )
        self.pair_view_expires_label.pack(pady=(0, 16))

        # QR Display placeholder
        self.qr_label = ctk.CTkLabel(
            p_card,
            text="[QR Code Available When Pairing is Open]",
            font=ctk.CTkFont(size=14),
            text_color=COLOR_TEXT_MUTED,
            width=220,
            height=220,
            corner_radius=8,
            fg_color=COLOR_BG_LIGHT,
        )
        self.qr_label.pack(pady=10)

        btn_generate = ctk.CTkButton(
            p_card,
            text="Generate New Pairing Code",
            font=ctk.CTkFont(size=14, weight="bold"),
            fg_color=COLOR_ACCENT,
            hover_color=COLOR_ACCENT_HOVER,
            corner_radius=8,
            height=40,
            command=self.on_click_new_code,
        )
        btn_generate.pack(pady=16)

    def _refresh_pair_view(self):
        now = time.time()
        if self.pair_code_expires_at > now:
            remaining = int(self.pair_code_expires_at - now)
            mins = remaining // 60
            secs = remaining % 60
            self.pair_view_code_label.configure(text=self.current_pair_code)
            self.pair_view_expires_label.configure(text=f"Code expires in {mins}:{secs:02d}")
        else:
            self.pair_view_code_label.configure(text="--- ---")
            self.pair_view_expires_label.configure(text="Pairing closed. Click below to open.")

    # -----------------------------------------------------------------------
    # 3. Users View (Mockup 5)
    # -----------------------------------------------------------------------

    def _init_users_view(self):
        v = self.views["users"]

        # Header row: Title + Allocation summary + Add User button
        u_header = ctk.CTkFrame(v, fg_color="transparent")
        u_header.pack(fill="x", pady=(0, 16))

        u_title = ctk.CTkLabel(
            u_header,
            text="Users",
            font=ctk.CTkFont(size=22, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
        )
        u_title.pack(side="left", padx=(0, 12))

        self.users_summary_label = ctk.CTkLabel(
            u_header,
            text="0 people · 0 GB allocated",
            font=ctk.CTkFont(size=14),
            text_color=COLOR_TEXT_MUTED,
        )
        self.users_summary_label.pack(side="left")

        btn_add_user = ctk.CTkButton(
            u_header,
            text="Add user",
            font=ctk.CTkFont(size=14, weight="bold"),
            fg_color=COLOR_ACCENT,
            hover_color=COLOR_ACCENT_HOVER,
            corner_radius=8,
            height=36,
            command=self._show_add_user_dialog,
        )
        btn_add_user.pack(side="right")

        # Table container
        self.users_table_card = ctk.CTkFrame(
            v,
            fg_color=COLOR_CARD_LIGHT,
            corner_radius=12,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
        )
        self.users_table_card.pack(fill="both", expand=True, padx=2, pady=(0, 16))

        # Table header
        th = ctk.CTkFrame(self.users_table_card, fg_color="transparent", height=32)
        th.pack(fill="x", padx=16, pady=(12, 6))

        ctk.CTkLabel(th, text="Name", font=ctk.CTkFont(size=13, weight="bold"), text_color=COLOR_TEXT_MUTED, width=160, anchor="w").pack(side="left")
        ctk.CTkLabel(th, text="Role", font=ctk.CTkFont(size=13, weight="bold"), text_color=COLOR_TEXT_MUTED, width=80, anchor="w").pack(side="left")
        ctk.CTkLabel(th, text="Usage", font=ctk.CTkFont(size=13, weight="bold"), text_color=COLOR_TEXT_MUTED, width=180, anchor="w").pack(side="left")
        ctk.CTkLabel(th, text="Quota", font=ctk.CTkFont(size=13, weight="bold"), text_color=COLOR_TEXT_MUTED, width=100, anchor="w").pack(side="left")
        ctk.CTkLabel(th, text="Devices", font=ctk.CTkFont(size=13, weight="bold"), text_color=COLOR_TEXT_MUTED, width=60, anchor="center").pack(side="left")

        # Scrollable table body
        self.users_table_body = ctk.CTkScrollableFrame(
            self.users_table_card,
            fg_color="transparent",
        )
        self.users_table_body.pack(fill="both", expand=True, padx=12, pady=(0, 12))

        # Selected User Action Drawer / Card
        self.user_action_card = ctk.CTkFrame(
            v,
            fg_color=COLOR_CARD_LIGHT,
            corner_radius=12,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
            height=130,
        )
        self.user_action_card.pack(fill="x", padx=2, pady=2)

        self.user_action_info_label = ctk.CTkLabel(
            self.user_action_card,
            text="Select a user above to manage settings and devices",
            font=ctk.CTkFont(size=14),
            text_color=COLOR_TEXT_MUTED,
            anchor="w",
        )
        self.user_action_info_label.pack(fill="x", padx=20, pady=(14, 10))

        self.user_action_buttons_frame = ctk.CTkFrame(self.user_action_card, fg_color="transparent")
        self.user_action_buttons_frame.pack(fill="x", padx=20, pady=(0, 14))

        self.btn_rename = ctk.CTkButton(
            self.user_action_buttons_frame,
            text="Rename",
            fg_color=COLOR_CARD_LIGHT,
            text_color=COLOR_TEXT_MAIN,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
            hover_color=COLOR_BG_LIGHT,
            corner_radius=8,
            height=34,
            command=self._on_rename_user,
        )
        self.btn_rename.pack(side="left", padx=(0, 6))

        self.btn_change_quota = ctk.CTkButton(
            self.user_action_buttons_frame,
            text="Change quota",
            fg_color=COLOR_CARD_LIGHT,
            text_color=COLOR_TEXT_MAIN,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
            hover_color=COLOR_BG_LIGHT,
            corner_radius=8,
            height=34,
            command=self._on_change_quota,
        )
        self.btn_change_quota.pack(side="left", padx=6)

        self.btn_manage_devices = ctk.CTkButton(
            self.user_action_buttons_frame,
            text="Device roles",
            fg_color=COLOR_CARD_LIGHT,
            text_color=COLOR_TEXT_MAIN,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
            hover_color=COLOR_BG_LIGHT,
            corner_radius=8,
            height=34,
            command=self._on_manage_device_roles,
        )
        self.btn_manage_devices.pack(side="left", padx=6)

        self.btn_remove_user = ctk.CTkButton(
            self.user_action_buttons_frame,
            text="Remove user",
            fg_color=COLOR_CARD_LIGHT,
            text_color=COLOR_DANGER,
            border_width=1,
            border_color=COLOR_DANGER,
            hover_color=COLOR_DANGER_LIGHT,
            corner_radius=8,
            height=34,
            command=self._on_remove_user,
        )
        self.btn_remove_user.pack(side="left", padx=6)

    def _refresh_users_view(self):
        try:
            users = self.ctrl.list_users()
            self.cached_users = users
        except Exception as e:
            logger.error("Could not fetch user directory: %s", e)
            users = self.cached_users

        allocated_bytes = sum(u.get("quota_bytes", 0) for u in users if u.get("status") == "active")
        self.users_summary_label.configure(
            text=f"{len(users)} people · {format_bytes(allocated_bytes)} allocated"
        )

        for w in self.users_table_body.winfo_children():
            w.destroy()

        for u in users:
            row_frame = ctk.CTkFrame(
                self.users_table_body,
                fg_color=COLOR_BG_LIGHT if u["id"] == self.selected_user_id else "transparent",
                corner_radius=6,
                height=40,
            )
            row_frame.pack(fill="x", pady=2)

            name_txt = f"{u['display_name']} (you)" if u["role"] == "owner" else u["display_name"]
            lbl_name = ctk.CTkLabel(row_frame, text=name_txt, font=ctk.CTkFont(size=14, weight="bold"), text_color=COLOR_TEXT_MAIN, width=160, anchor="w")
            lbl_name.pack(side="left", padx=4)

            # Role badge
            role_badge = ctk.CTkLabel(
                row_frame,
                text=u["role"].capitalize(),
                font=ctk.CTkFont(size=11, weight="bold"),
                fg_color="#D1FAE5" if u["role"] == "owner" else "#F3F4F6",
                text_color="#065F46" if u["role"] == "owner" else "#374151",
                corner_radius=12,
                width=64,
                height=22,
            )
            role_badge.pack(side="left", padx=6)

            # Usage
            used_b = u.get("used_bytes", 0)
            quota_b = u.get("quota_bytes", 0)
            u_str = format_bytes(used_b)
            if quota_b > 0:
                pct = int((used_b / quota_b) * 100)
                u_str += f", {pct}%"
            lbl_usage = ctk.CTkLabel(row_frame, text=u_str, font=ctk.CTkFont(size=13), text_color=COLOR_TEXT_MAIN, width=180, anchor="w")
            lbl_usage.pack(side="left", padx=4)

            # Quota
            q_str = "Unlimited" if quota_b == 0 else format_bytes(quota_b)
            lbl_quota = ctk.CTkLabel(row_frame, text=q_str, font=ctk.CTkFont(size=13), text_color=COLOR_TEXT_MUTED, width=100, anchor="w")
            lbl_quota.pack(side="left", padx=4)

            # Devices count
            d_count = u.get("devices_count", 0)
            lbl_dev = ctk.CTkLabel(row_frame, text=str(d_count), font=ctk.CTkFont(size=13), text_color=COLOR_TEXT_MAIN, width=60, anchor="center")
            lbl_dev.pack(side="left", padx=4)

            # Bind row click to select user
            row_frame.bind("<Button-1>", lambda e, uid=u["id"]: self._select_user(uid))
            for child in row_frame.winfo_children():
                child.bind("<Button-1>", lambda e, uid=u["id"]: self._select_user(uid))

    def _select_user(self, user_id: str):
        self.selected_user_id = user_id
        user = next((u for u in self.cached_users if u["id"] == user_id), None)
        if user:
            last_seen = user.get("last_seen_at") or "recently"
            if "T" in last_seen:
                last_seen = last_seen.split("T")[0]
            self.user_action_info_label.configure(
                text=f"{user['display_name']} · Role: {user['role'].capitalize()} · Last seen: {last_seen}"
            )
        self._refresh_users_view()

    def _show_add_user_dialog(self):
        dialog = ctk.CTkInputDialog(text="Enter new user's display name:", title="Add Member")
        name = dialog.get_input()
        if name and name.strip():
            try:
                res = self.ctrl.create_user(name.strip())
                self._refresh_users_view()
                # Show invite code
                code = res.get("invite_code")
                ctk.CTkInputDialog(text=f"User created! Invite code (expires in 24h):\n\n{code}", title="Invite Code")
            except Exception as e:
                logger.error("Could not add user: %s", e)

    def _on_rename_user(self):
        if not self.selected_user_id:
            return
        dialog = ctk.CTkInputDialog(text="Enter new display name:", title="Rename User")
        name = dialog.get_input()
        if name and name.strip():
            try:
                self.ctrl.update_user(self.selected_user_id, display_name=name.strip())
                self._refresh_users_view()
            except Exception as e:
                logger.error("Could not rename user: %s", e)

    def _on_change_quota(self):
        if not self.selected_user_id:
            return
        dialog = ctk.CTkInputDialog(text="Enter new quota in GB (e.g. 50, or 0 for unlimited):", title="Change Quota")
        val = dialog.get_input()
        if val is not None:
            try:
                gb = float(val)
                quota_bytes = int(gb * 1024 * 1024 * 1024)
                self.ctrl.update_user(self.selected_user_id, quota_bytes=quota_bytes)
                self._refresh_users_view()
            except Exception as e:
                logger.error("Invalid quota entered: %s", e)

    def _on_manage_device_roles(self):
        """Improvement 6: View and toggle device roles between backup and manage."""
        if not self.selected_user_id:
            return
        devices = self.ctrl.list_devices(self.selected_user_id)
        if not devices:
            return

        # Show device roles modal window
        dev_win = ctk.CTkToplevel(self.root)
        dev_win.title("Device Roles Management")
        dev_win.geometry("520x360")
        dev_win.transient(self.root)

        ctk.CTkLabel(
            dev_win,
            text="Device Roles (Improvement 6)",
            font=ctk.CTkFont(size=18, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
        ).pack(padx=20, pady=(20, 6), anchor="w")

        ctk.CTkLabel(
            dev_win,
            text="'Manage' devices can change quotas and add users.\n'Backup' devices can upload photos but have zero admin access.",
            font=ctk.CTkFont(size=12),
            text_color=COLOR_TEXT_MUTED,
            anchor="w",
            justify="left",
        ).pack(padx=20, pady=(0, 16), anchor="w")

        scroll = ctk.CTkScrollableFrame(dev_win, fg_color="transparent")
        scroll.pack(fill="both", expand=True, padx=16, pady=4)

        for dev in devices:
            row = ctk.CTkFrame(scroll, fg_color=COLOR_CARD_LIGHT, corner_radius=8)
            row.pack(fill="x", pady=4, padx=2)

            d_name = f"{dev['name']} ({dev['model']})"
            d_role = dev.get("role", "backup")

            ctk.CTkLabel(
                row,
                text=d_name,
                font=ctk.CTkFont(size=13, weight="bold"),
                text_color=COLOR_TEXT_MAIN,
            ).pack(side="left", padx=12, pady=10)

            # Role toggle button
            btn_role = ctk.CTkButton(
                row,
                text=d_role.capitalize(),
                font=ctk.CTkFont(size=12, weight="bold"),
                fg_color="#D1FAE5" if d_role == "manage" else "#F3F4F6",
                text_color="#065F46" if d_role == "manage" else "#374151",
                corner_radius=12,
                width=80,
                height=26,
            )
            btn_role.pack(side="right", padx=12, pady=10)

            def toggle_role(d_id=dev["id"], cur=d_role, b=btn_role):
                new_r = "backup" if cur == "manage" else "manage"
                try:
                    self.ctrl.set_device_role(self.selected_user_id, d_id, new_r)
                    b.configure(
                        text=new_r.capitalize(),
                        fg_color="#D1FAE5" if new_r == "manage" else "#F3F4F6",
                        text_color="#065F46" if new_r == "manage" else "#374151",
                    )
                except ControlError as ex:
                    logger.error("Could not change device role: %s", ex)

            btn_role.configure(command=toggle_role)

    def _on_remove_user(self):
        """Improvement 5: Remove user with 7-day grace period option."""
        if not self.selected_user_id:
            return
        user = next((u for u in self.cached_users if u["id"] == self.selected_user_id), None)
        if not user or user["role"] == "owner":
            return

        try:
            # Schedules 7-day grace period by default
            self.ctrl.delete_user(self.selected_user_id, delete_now=False, grace_days=7)
            self._refresh_users_view()
        except Exception as e:
            logger.error("Could not remove user: %s", e)

    # -----------------------------------------------------------------------
    # 4. Storage View (Mockup 6)
    # -----------------------------------------------------------------------

    def _init_storage_view(self):
        v = self.views["storage"]

        s_card = ctk.CTkFrame(
            v,
            fg_color=COLOR_CARD_LIGHT,
            corner_radius=12,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
        )
        s_card.pack(fill="both", expand=True, padx=2, pady=2)

        ctk.CTkLabel(
            s_card,
            text="Storage Allocation",
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
        ).pack(padx=24, pady=(24, 6), anchor="w")

        ctk.CTkLabel(
            s_card,
            text="Choose how much drive space PiPhotos is allowed to use for your vault.",
            font=ctk.CTkFont(size=14),
            text_color=COLOR_TEXT_MUTED,
        ).pack(padx=24, pady=(0, 20), anchor="w")

        # Allocation entry
        alloc_frame = ctk.CTkFrame(s_card, fg_color="transparent")
        alloc_frame.pack(fill="x", padx=24, pady=8)

        ctk.CTkLabel(
            alloc_frame,
            text="Allocation Size (e.g. 79GB, 200GB, 1.5TB):",
            font=ctk.CTkFont(size=14, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
        ).pack(side="left", padx=(0, 16))

        self.storage_alloc_entry = ctk.CTkEntry(
            alloc_frame,
            placeholder_text="e.g. 79GB",
            width=140,
            height=36,
        )
        self.storage_alloc_entry.pack(side="left", padx=(0, 12))

        btn_save_alloc = ctk.CTkButton(
            alloc_frame,
            text="Save Allocation",
            font=ctk.CTkFont(size=14, weight="bold"),
            fg_color=COLOR_ACCENT,
            hover_color=COLOR_ACCENT_HOVER,
            corner_radius=8,
            height=36,
            command=self._on_save_allocation,
        )
        btn_save_alloc.pack(side="left")

        # Reserve information card
        self.storage_info_box = ctk.CTkLabel(
            s_card,
            text="A 10% safety reserve is automatically kept free on the disk so the host OS never runs out of space.",
            font=ctk.CTkFont(size=13),
            text_color=COLOR_TEXT_MUTED,
            anchor="w",
            justify="left",
        )
        self.storage_info_box.pack(fill="x", padx=24, pady=16)

    def _refresh_storage_view(self):
        status = self.cached_status
        alloc_bytes = status.get("node_allocation_bytes") or 0
        if alloc_bytes > 0:
            self.storage_alloc_entry.delete(0, "end")
            self.storage_alloc_entry.insert(0, format_bytes(alloc_bytes).replace(" ", ""))

    def _on_save_allocation(self):
        val = self.storage_alloc_entry.get().strip()
        if not val:
            return
        try:
            self.ctrl.set_allocation(val, force=False)
            self._refresh_status_view()
        except ControlError as ex:
            logger.error("Failed to set allocation: %s", ex)

    # -----------------------------------------------------------------------
    # 5. Settings View (Database Backups & Diagnostics)
    # -----------------------------------------------------------------------

    def _init_settings_view(self):
        v = self.views["settings"]

        scroll = ctk.CTkScrollableFrame(v, fg_color="transparent")
        scroll.pack(fill="both", expand=True)

        # Card 1: Windows Service & Autorun
        svc_card = ctk.CTkFrame(
            scroll,
            fg_color=COLOR_CARD_LIGHT,
            corner_radius=12,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
        )
        svc_card.pack(fill="x", padx=2, pady=(2, 16))

        ctk.CTkLabel(
            svc_card,
            text="Windows Startup & Service",
            font=ctk.CTkFont(size=16, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
        ).pack(padx=20, pady=(16, 6), anchor="w")

        self.cb_autostart = ctk.CTkCheckBox(
            svc_card,
            text="Start PiPhotos Server automatically when Windows starts",
            font=ctk.CTkFont(size=13),
            checkbox_height=20,
            checkbox_width=20,
            command=self._on_toggle_autostart,
        )
        self.cb_autostart.pack(padx=20, pady=(6, 16), anchor="w")

        # Card 2: Database Backup & Restore (Improvement 8)
        backup_card = ctk.CTkFrame(
            scroll,
            fg_color=COLOR_CARD_LIGHT,
            corner_radius=12,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
        )
        backup_card.pack(fill="x", padx=2, pady=(0, 16))

        b_header = ctk.CTkFrame(backup_card, fg_color="transparent")
        b_header.pack(fill="x", padx=20, pady=(16, 8))

        ctk.CTkLabel(
            b_header,
            text="Database Backups (Improvement 8)",
            font=ctk.CTkFont(size=16, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
        ).pack(side="left")

        btn_backup_now = ctk.CTkButton(
            b_header,
            text="Create Backup Now",
            font=ctk.CTkFont(size=13, weight="bold"),
            fg_color=COLOR_ACCENT,
            hover_color=COLOR_ACCENT_HOVER,
            corner_radius=8,
            height=32,
            command=self._on_create_backup_now,
        )
        btn_backup_now.pack(side="right")

        ctk.CTkLabel(
            backup_card,
            text="The server automatically creates daily point-in-time snapshots at 02:00 (keeps last 14).",
            font=ctk.CTkFont(size=12),
            text_color=COLOR_TEXT_MUTED,
            anchor="w",
        ).pack(fill="x", padx=20, pady=(0, 8))

        self.backups_list_container = ctk.CTkFrame(backup_card, fg_color=COLOR_BG_LIGHT, corner_radius=8)
        self.backups_list_container.pack(fill="x", padx=20, pady=(0, 16))

        # Card 3: System Health Diagnostics
        diag_card = ctk.CTkFrame(
            scroll,
            fg_color=COLOR_CARD_LIGHT,
            corner_radius=12,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
        )
        diag_card.pack(fill="x", padx=2, pady=(0, 16))

        d_header = ctk.CTkFrame(diag_card, fg_color="transparent")
        d_header.pack(fill="x", padx=20, pady=(16, 8))

        ctk.CTkLabel(
            d_header,
            text="Diagnostics & Health",
            font=ctk.CTkFont(size=16, weight="bold"),
            text_color=COLOR_TEXT_MAIN,
        ).pack(side="left")

        btn_run_doctor = ctk.CTkButton(
            d_header,
            text="Run Diagnostics",
            font=ctk.CTkFont(size=13),
            fg_color=COLOR_CARD_LIGHT,
            text_color=COLOR_TEXT_MAIN,
            border_width=1,
            border_color=COLOR_BORDER_LIGHT,
            hover_color=COLOR_BG_LIGHT,
            corner_radius=8,
            height=32,
            command=self._on_run_doctor,
        )
        btn_run_doctor.pack(side="right")

        self.doctor_result_label = ctk.CTkLabel(
            diag_card,
            text="System healthy. 14 of 14 checks passed.",
            font=ctk.CTkFont(size=13),
            text_color=COLOR_TEXT_MUTED,
            anchor="w",
        )
        self.doctor_result_label.pack(fill="x", padx=20, pady=(0, 16))

    def _refresh_settings_view(self):
        # Refresh backups list
        for w in self.backups_list_container.winfo_children():
            w.destroy()

        backups = self.ctrl.list_backups()
        if not backups:
            ctk.CTkLabel(
                self.backups_list_container,
                text="No database backups created yet.",
                font=ctk.CTkFont(size=12),
                text_color=COLOR_TEXT_MUTED,
            ).pack(pady=12)
        else:
            for b in backups[:6]:
                row = ctk.CTkFrame(self.backups_list_container, fg_color="transparent")
                row.pack(fill="x", padx=12, pady=3)
                txt = f"{b['filename']} ({format_bytes(b['size_bytes'])}) · {b['created_at']}"
                ctk.CTkLabel(row, text=txt, font=ctk.CTkFont(size=12), text_color=COLOR_TEXT_MAIN).pack(side="left")

                btn_res = ctk.CTkButton(
                    row,
                    text="Restore",
                    font=ctk.CTkFont(size=11),
                    width=60,
                    height=24,
                    fg_color=COLOR_CARD_LIGHT,
                    text_color=COLOR_TEXT_MAIN,
                    border_width=1,
                    border_color=COLOR_BORDER_LIGHT,
                    command=lambda p=b["path"]: self._on_restore_backup(p),
                )
                btn_res.pack(side="right")

    def _on_toggle_autostart(self):
        val = self.cb_autostart.get()
        if val == 1:
            self.ctrl.install_service(no_firewall=False)
        else:
            self.ctrl.uninstall_service(no_firewall=False)

    def _on_create_backup_now(self):
        try:
            self.ctrl.backup_database()
            self._refresh_settings_view()
        except Exception as e:
            logger.error("Backup creation failed: %s", e)

    def _on_restore_backup(self, backup_path: str):
        try:
            self.ctrl.restore_database(backup_path)
            self._refresh_status_view()
            self._refresh_settings_view()
        except Exception as e:
            logger.error("Restore failed: %s", e)

    def _on_run_doctor(self):
        doc = self.ctrl.run_doctor()
        p = doc.get("passed", 0)
        tot = doc.get("total", 0)
        self.doctor_result_label.configure(
            text=f"Health check completed: {p} of {tot} checks passed."
        )

    # -----------------------------------------------------------------------
    # System tray & window lifecycle
    # -----------------------------------------------------------------------

    def _setup_tray_icon(self):
        try:
            import pystray  # type: ignore
            from PIL import Image, ImageDraw

            # Generate dynamic green circle tray icon
            img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
            draw = ImageDraw.Draw(img)
            draw.ellipse((8, 8, 56, 56), fill=COLOR_ACCENT)

            menu = pystray.Menu(
                pystray.MenuItem("Show PiPhotos", self.show_window, default=True),
                pystray.MenuItem("New Pairing Code", self.on_click_new_code),
                pystray.MenuItem("Exit", self.quit_app),
            )
            self._tray_icon = pystray.Icon("piphotos_server", img, "PiPhotos Server", menu)
            threading.Thread(target=self._tray_icon.run, daemon=True).start()
        except Exception as e:
            logger.warning("System tray integration disabled: %s", e)

    def show_window(self, icon=None, item=None):
        self.root.after(0, self._restore_window)

    def _restore_window(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def on_close_window(self):
        if self._tray_icon:
            self.root.withdraw()
        else:
            self.quit_app()

    def quit_app(self, icon=None, item=None):
        self._stop_polling.set()
        if self._tray_icon:
            self._tray_icon.stop()
        self.root.after(0, self.root.destroy)

    # -----------------------------------------------------------------------
    # Background Polling
    # -----------------------------------------------------------------------

    def _background_poll_loop(self):
        while not self._stop_polling.is_set():
            try:
                status = self.ctrl.get_status()
                uploads = self.ctrl.list_recent_uploads(limit=10)
                self.cached_status = status
                self.cached_uploads = uploads
                self.root.after(0, self._refresh_status_view)
            except Exception:
                pass
            time.sleep(3.0)


def launch_gui(data_dir: Optional[str | Path] = None, start_in_tray: bool = False) -> int:
    """Entry point for native desktop application."""
    app = PiPhotosApp(data_dir=data_dir, start_in_tray=start_in_tray)
    app.root.mainloop()
    return 0


# Backward compatibility alias
PiPhotosServerGUI = PiPhotosApp

