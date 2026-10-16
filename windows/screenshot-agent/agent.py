"""Windows tray application for the desktop part of the lifelog corpus."""
import argparse
import asyncio
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox

import config as settings
from capture import enable_dpi, monitors, take
from store import Store
from transport import upload


class Agent:
    def __init__(self, directory, config):
        self.directory, self.config = directory, config
        self.store = Store(directory)
        self.stop = threading.Event()
        self.paused = threading.Event()
        self.wake = threading.Event()
        self.lock = threading.Lock()
        self.capture_status = "開始待ち"
        self.send_status = "送信待ち"
        self.last_capture = "まだありません"
        self.last_upload = "まだありません"
        if not config.get("token"):
            self.paused.set()

    def snapshot(self):
        with self.lock:
            return dict(self.config)

    def process_one(self):
        from ocr import recognize
        row = self.store.next()
        if row is None:
            return False
        try:
            if row["jpeg"] is None:
                self.send_status = "画面の文字を読み取り中"
                async def read():
                    return await asyncio.wait_for(recognize(row["png"]), timeout=60)
                text, jpeg, caption_jpeg = asyncio.run(read())
                self.store.prepared(row["id"], text, jpeg, caption_jpeg)
                row = self.store.get(row["id"])
            if self.stop.is_set() or self.paused.is_set():
                return False
            self.send_status = "送信中"
            upload(self.snapshot(), row)
            self.store.acknowledge(row["id"])
            self.last_upload = time.strftime("%H:%M:%S")
            self.send_status = "送信済み"
        except Exception as exc:
            # Never log response bodies, request URLs, OCR, or credentials.
            reason = str(exc) if isinstance(exc, RuntimeError) else type(exc).__name__
            self.store.failed(row["id"], row["attempts"], reason)
            self.send_status = reason
            logging.warning("Processing deferred: %s", reason)
        return True

    def capture_loop(self):
        due = time.monotonic()
        while not self.stop.is_set():
            if self.paused.is_set():
                self.wake.clear()
                self.stop.wait(0.5)
                due = time.monotonic()
                continue
            if self.wake.is_set():
                self.wake.clear()
                due = time.monotonic()
            if time.monotonic() < due:
                self.wake.wait(min(0.5, due - time.monotonic()))
                continue
            config = self.snapshot()
            try:
                if shutil.disk_usage(self.directory).free < 512 * 1024 * 1024:
                    raise RuntimeError("空き容量が512MiB未満です。容量を確保すると撮影を再開します")
                count = take(self.store, config["device_id"], config["masked_apps"])
                self.capture_status = f"{count}画面を保存" if count else "画面ロック中・画面を取得できません"
                if count:
                    self.last_capture = time.strftime("%H:%M:%S")
            except Exception as exc:
                self.capture_status = str(exc) if isinstance(exc, RuntimeError) else "撮影失敗: " + type(exc).__name__
                logging.warning("Capture deferred: %s", self.capture_status)
            # No burst of invented past frames after resume from sleep.
            due += config["interval_seconds"]
            if due <= time.monotonic():
                due = time.monotonic() + config["interval_seconds"]

    def send_loop(self):
        while not self.stop.is_set():
            if self.paused.is_set():
                self.stop.wait(0.5)
                continue
            try:
                if not self.process_one():
                    self.stop.wait(1)
            except Exception as exc:
                self.send_status = "キュー読取失敗: " + type(exc).__name__
                logging.warning(self.send_status)
                self.stop.wait(5)

    def start(self):
        self.threads = [threading.Thread(target=f, daemon=True) for f in (self.capture_loop, self.send_loop)]
        for thread in self.threads:
            thread.start()


def startup_command():
    executable = Path(sys.executable).with_name("pythonw.exe")
    return subprocess.list2cmdline([str(executable), str(Path(__file__).resolve()), "--background"])


def startup_enabled():
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as key:
            return winreg.QueryValueEx(key, "LifelogScreenshot")[0] == startup_command()
    except FileNotFoundError:
        return False


def set_startup(enabled):
    import winreg
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as key:
        if enabled:
            winreg.SetValueEx(key, "LifelogScreenshot", 0, winreg.REG_SZ, startup_command())
        else:
            try:
                winreg.DeleteValue(key, "LifelogScreenshot")
            except FileNotFoundError:
                pass


def run_ui(agent, background=False):
    import pystray
    from PIL import Image, ImageDraw
    root = tk.Tk()
    root.title("Lifelog — PCの画面を記録")
    root.geometry("680x800")
    root.minsize(620, 760)
    commands = queue.Queue()
    frame = ttk.Frame(root, padding=22)
    frame.pack(fill="both", expand=True)
    ttk.Label(frame, text="PCの画面を記録", font=("Yu Gothic UI", 20, "bold")).pack(anchor="w")
    ttk.Label(frame, text="画面が変わらなくても、各画面を毎回記録します。", padding=(0, 8)).pack(anchor="w")
    status = tk.StringVar()
    ttk.Label(frame, textvariable=status, justify="left", wraplength=615).pack(anchor="w", pady=8)
    buttons = ttk.Frame(frame)
    buttons.pack(fill="x", pady=8)

    def toggle():
        if agent.paused.is_set():
            try:
                settings.validate(agent.snapshot())
            except ValueError as exc:
                messagebox.showerror("設定を確認してください", str(exc), parent=root)
                return
            agent.paused.clear()
            agent.wake.set()
        else:
            agent.paused.set()

    pause = ttk.Button(buttons, command=toggle)
    pause.pack(side="left")
    ttk.Button(buttons, text="未送信を再試行", command=agent.store.retry_now).pack(side="left", padx=8)
    ttk.Button(buttons, text="保存フォルダ", command=lambda: os.startfile(agent.directory)).pack(side="left")
    ttk.Button(buttons, text="終了", command=lambda: commands.put("quit")).pack(side="right")
    ttk.Separator(frame).pack(fill="x", pady=12)
    fields = {}
    labels = [("endpoint", "接続先"), ("interval_seconds", "撮影間隔（秒）"),
              ("access_id", "Access Client ID"), ("access_secret", "Access Client Secret"),
              ("token", "取り込みトークン")]
    form = ttk.Frame(frame)
    form.pack(fill="x")
    form.columnconfigure(1, weight=1)
    for i, (name, label) in enumerate(labels):
        ttk.Label(form, text=label).grid(row=i, column=0, sticky="w", pady=5, padx=(0, 12))
        value = tk.StringVar(value=str(agent.config[name]))
        fields[name] = value
        ttk.Entry(form, textvariable=value, show="●" if name in ("token", "access_secret", "access_id") else "").grid(row=i, column=1, sticky="ew", pady=5)
    ttk.Label(form, text="黒く塗るアプリ\n（exe名かフルパスを\n1行に1つ）").grid(row=len(labels), column=0, sticky="nw", pady=5, padx=(0, 12))
    masked = tk.Text(form, height=4, font=("Consolas", 10))
    masked.insert("1.0", "\n".join(agent.config["masked_apps"]))
    masked.grid(row=len(labels), column=1, sticky="ew", pady=5)
    start = tk.BooleanVar(value=startup_enabled())
    ttk.Checkbutton(frame, text="Windowsへのサインイン時に起動", variable=start).pack(anchor="w", pady=12)

    def apply():
        config = agent.snapshot()
        config.update({k: v.get().strip() for k, v in fields.items()})
        # Explorer's "Copy as path" wraps the path in quotes.
        lines = [line.strip().strip('"').strip() for line in masked.get("1.0", "end").splitlines()]
        config["masked_apps"] = list(dict.fromkeys(line for line in lines if line))
        try:
            config["interval_seconds"] = int(config["interval_seconds"])
            settings.save(config, agent.directory)
            set_startup(start.get())
            with agent.lock:
                agent.config = config
            agent.wake.set()
            messagebox.showinfo("保存しました", "設定を保存しました。停止中の場合は「記録を開始」で開始します。", parent=root)
        except (ValueError, OSError) as exc:
            messagebox.showerror("保存できません", str(exc), parent=root)

    ttk.Button(frame, text="設定を保存", command=apply).pack(anchor="w")
    ttk.Label(frame, text="未送信の画像はPCに保持し、保存成功後に削除します。\n閉じるボタンでは常駐を続けます。終了はトレイメニューから。", padding=(0, 16)).pack(anchor="w")
    picture = Image.new("RGB", (64, 64), "#14334b")
    ImageDraw.Draw(picture).rectangle((12, 15, 52, 44), outline="white", width=4)
    icon = pystray.Icon("lifelog-screenshot", picture, "Lifelog PC", menu=pystray.Menu(
        pystray.MenuItem("状態・設定", lambda: commands.put("show"), default=True),
        pystray.MenuItem("一時停止 / 再開", lambda: commands.put("toggle")),
        pystray.MenuItem("終了", lambda: commands.put("quit"))))

    def quit_app():
        agent.paused.set()
        agent.stop.set()
        agent.wake.set()
        icon.stop()
        root.destroy()

    def refresh():
        while not commands.empty():
            command = commands.get_nowait()
            if command == "quit":
                quit_app()
                return
            if command == "show":
                root.deiconify()
                root.lift()
            if command == "toggle":
                toggle()
        try:
            stats = agent.store.stats()
            mode = "一時停止中" if agent.paused.is_set() else f"記録中 · {agent.snapshot()['interval_seconds']}秒ごと"
            status.set(f"{mode}\n最後の撮影 {agent.last_capture} · 最後の送信 {agent.last_upload}\n"
                       f"未送信 {stats['pending']}枚 / {stats['bytes'] / 1024 / 1024:.1f} MiB\n"
                       f"{agent.capture_status}\n{agent.send_status}")
            pause.configure(text="記録を開始" if agent.paused.is_set() else "一時停止")
            icon.title = "Lifelog PC · " + mode
        except sqlite_error_types():
            status.set("保存先の状態を確認できません")
        root.after(1000, refresh)

    icon.run_detached()
    root.protocol("WM_DELETE_WINDOW", root.withdraw)
    if background and agent.config.get("token"):
        root.withdraw()
    agent.start()
    refresh()
    root.mainloop()


def single_instance(directory):
    """The open lock file if this is the only running copy, otherwise None.

    The OS releases the lock when the process dies, so a stale file never
    blocks a restart. The lock is taken before anything reads the file:
    reading byte 0 while another copy holds it raises PermissionError, which
    used to end in a startup failure instead of "already running".
    Windows allows locking past the end of an empty file.
    """
    import msvcrt
    lock = open(Path(directory) / "agent.lock", "a+b")
    lock.seek(0)
    try:
        msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
    except OSError:
        lock.close()
        return None
    return lock


def sqlite_error_types():
    import sqlite3
    return (sqlite3.Error, OSError)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", type=Path, default=settings.data_dir())
    parser.add_argument("--background", action="store_true")
    parser.add_argument("--capture-once", action="store_true", help="Save one capture locally; no upload")
    parser.add_argument("--process-once", action="store_true", help="OCR and send one pending capture")
    parser.add_argument("--doctor", action="store_true")
    args = parser.parse_args()
    enable_dpi()
    args.data_dir.mkdir(parents=True, exist_ok=True)
    handler = RotatingFileHandler(args.data_dir / "agent.log", maxBytes=1024 * 1024, backupCount=3, encoding="utf-8")
    logging.basicConfig(handlers=[handler], level=logging.INFO)
    lock = single_instance(args.data_dir)
    if lock is None:
        messagebox.showinfo("Lifelog", "既に起動しています。タスクトレイから開いてください。")
        return
    try:
        config = settings.load(args.data_dir)
        agent = Agent(args.data_dir, config)
        if args.doctor:
            from winrt.windows.globalization import Language
            from winrt.windows.media.ocr import OcrEngine
            print(json.dumps(dict(monitors=monitors(), japanese_ocr=OcrEngine.try_create_from_language(Language("ja")) is not None,
                                  max_ocr_dimension=OcrEngine.max_image_dimension, configured=bool(config.get("token"))), ensure_ascii=False))
        elif args.capture_once:
            print(json.dumps(dict(captured=take(agent.store, config["device_id"], config["masked_apps"]), **agent.store.stats())))
        elif args.process_once:
            settings.validate(config)
            agent.process_one()
            print(json.dumps(agent.store.stats()))
        else:
            run_ui(agent, args.background)
    finally:
        lock.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        logging.exception("Application failed")
        if sys.stdout is None:
            messagebox.showerror("Lifelog 起動失敗", type(exc).__name__ + "。保存フォルダのagent.logを確認してください。")
        else:
            raise
