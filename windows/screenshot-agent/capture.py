"""Native-resolution capture of each attached display, with no change filter."""
import ctypes
from ctypes import wintypes
from datetime import datetime, timezone
import io
import uuid
from PIL import ImageGrab


class MonitorInfo(ctypes.Structure):
    _fields_ = [("size", wintypes.DWORD), ("monitor", wintypes.RECT),
                ("work", wintypes.RECT), ("flags", wintypes.DWORD), ("device", wintypes.WCHAR * 32)]


def enable_dpi():
    user = ctypes.WinDLL("user32", use_last_error=True)
    user.SetProcessDpiAwarenessContext.argtypes = [ctypes.c_void_p]
    user.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))


def monitors():
    user = ctypes.WinDLL("user32", use_last_error=True)
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HANDLE, wintypes.HDC,
                                      ctypes.POINTER(wintypes.RECT), wintypes.LPARAM)
    user.GetMonitorInfoW.argtypes = [wintypes.HANDLE, ctypes.POINTER(MonitorInfo)]
    user.EnumDisplayMonitors.argtypes = [wintypes.HDC, ctypes.c_void_p, callback_type, wintypes.LPARAM]
    found = []

    @callback_type
    def collect(handle, dc, rect, data):
        info = MonitorInfo()
        info.size = ctypes.sizeof(info)
        if user.GetMonitorInfoW(handle, ctypes.byref(info)):
            r = info.monitor
            found.append((info.device, (r.left, r.top, r.right, r.bottom)))
        return True

    if not user.EnumDisplayMonitors(None, None, collect, 0):
        raise ctypes.WinError(ctypes.get_last_error())
    return found


def desktop_available():
    """Do not produce a lock screen/secure-desktop frame as if it were work."""
    user = ctypes.WinDLL("user32", use_last_error=True)
    user.OpenInputDesktop.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    user.OpenInputDesktop.restype = wintypes.HANDLE
    user.SwitchDesktop.argtypes = [wintypes.HANDLE]
    user.CloseDesktop.argtypes = [wintypes.HANDLE]
    handle = user.OpenInputDesktop(0, False, 0x0100)  # DESKTOP_SWITCHDESKTOP
    if not handle:
        return False
    try:
        return bool(user.SwitchDesktop(handle))
    finally:
        user.CloseDesktop(handle)


def take(store, device_id):
    if not desktop_available():
        return 0
    capture_id = uuid.uuid4().hex
    taken_at = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    count = 0
    for name, rect in monitors():
        image = ImageGrab.grab(bbox=rect, all_screens=True).convert("RGB")
        data = io.BytesIO()
        image.save(data, format="PNG")
        store.add(taken_at, dict(capture_id=capture_id, monitor_id=name,
                                width=image.width, height=image.height, device_id=device_id), data.getvalue())
        count += 1
    return count
