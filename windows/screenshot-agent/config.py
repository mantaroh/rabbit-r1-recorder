"""User configuration; credential material is protected by Windows DPAPI."""
import base64
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
from urllib.parse import urlparse
import uuid


def data_dir():
    return Path(os.environ.get("LOCALAPPDATA", Path.home())) / "LifelogScreenshot"


class Blob(ctypes.Structure):
    _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]


def protect(value: bytes, decrypt=False) -> bytes:
    buffer = (ctypes.c_ubyte * len(value)).from_buffer_copy(value)
    source, target = Blob(len(value), buffer), Blob()
    dll = ctypes.WinDLL("crypt32", use_last_error=True)
    function = dll.CryptUnprotectData if decrypt else dll.CryptProtectData
    function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                         ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    function.restype = wintypes.BOOL
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        free = ctypes.WinDLL("kernel32").LocalFree
        free.argtypes = [ctypes.c_void_p]
        free.restype = ctypes.c_void_p
        free(target.data)


def defaults():
    return dict(endpoint="https://lifelog.mantaroh.org", interval_seconds=30,
                device_id="pc-" + uuid.uuid4().hex[:16], access_id="", access_secret="", token="")


def validate(config):
    url = urlparse(config["endpoint"])
    local = url.hostname in ("localhost", "127.0.0.1", "::1")
    if not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in ("", "/"):
        raise ValueError("接続先はパスを含まないURLを指定してください")
    if url.scheme != "https" and not (local and url.scheme == "http"):
        raise ValueError("接続先にはHTTPSが必要です（localhostを除く）")
    interval = config["interval_seconds"]
    if isinstance(interval, bool) or not isinstance(interval, int) or not 5 <= interval <= 3600:
        raise ValueError("撮影間隔は5〜3600秒の整数です")
    if not config["token"] or any(c in config["token"] for c in "\r\n"):
        raise ValueError("取り込みトークンを指定してください")
    if not local and (not config["access_id"] or not config["access_secret"]):
        raise ValueError("Cloudflare AccessのIDとSecretを指定してください")


def save(config, directory=None):
    validate(config)
    directory = directory or data_dir()
    directory.mkdir(parents=True, exist_ok=True)
    public = {k: v for k, v in config.items() if k not in ("access_id", "access_secret", "token")}
    secrets = {k: config[k] for k in ("access_id", "access_secret", "token")}
    public["credentials"] = base64.b64encode(protect(json.dumps(secrets).encode())).decode()
    temporary = directory / "config.tmp"
    temporary.write_text(json.dumps(public, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temporary, directory / "config.json")


def load(directory=None):
    path = (directory or data_dir()) / "config.json"
    if not path.exists():
        return defaults()
    config = json.loads(path.read_text(encoding="utf-8"))
    config.update(json.loads(protect(base64.b64decode(config.pop("credentials")), decrypt=True)))
    validate(config)
    return config
