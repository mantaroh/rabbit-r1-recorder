"""Native-resolution capture of each attached display, with no change filter.

Windows of the configured apps, and the Alt+Tab list, are painted black before
the frame is stored, only where they are actually visible.
"""
import ctypes
from ctypes import wintypes
from datetime import datetime, timezone
import io
import uuid
from PIL import ImageDraw, ImageGrab


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


# Alt+Tab (and Win+Tab) show live thumbnails of every window, so they are always blacked.
SWITCHER_CLASS = "XamlExplorerHostIslandWindow"
# Windows 11 rounds window corners: a window above lets what is behind show through
# its corners, so it is counted as covering only this far inside its edges.
CORNER = 16
WS_EX_TOPMOST, WS_EX_TRANSPARENT, WS_EX_TOOLWINDOW = 0x8, 0x20, 0x80
WS_EX_LAYERED, WS_EX_NOACTIVATE = 0x80000, 0x8000000


def normalized(masked_apps):
    return {name.lower().replace("/", "\\") for name in masked_apps}


def matches(path, masked):
    """An entry is either a bare exe name (every install) or a full path (that install only)."""
    return bool(path) and (path in masked or path.rsplit("\\", 1)[-1] in masked)


def process_path(hwnd, names):
    """Lower-case full exe path of the window's process; names caches it per pid for one enumeration."""
    user, kernel = ctypes.WinDLL("user32"), ctypes.WinDLL("kernel32")
    pid = wintypes.DWORD()
    user.GetWindowThreadProcessId(wintypes.HWND(hwnd), ctypes.byref(pid))
    if pid.value not in names:
        name = ""
        kernel.OpenProcess.restype = wintypes.HANDLE
        handle = kernel.OpenProcess(0x1000, False, pid.value)  # PROCESS_QUERY_LIMITED_INFORMATION
        if handle:
            buffer, size = ctypes.create_unicode_buffer(32768), wintypes.DWORD(32768)
            if kernel.QueryFullProcessImageNameW(wintypes.HANDLE(handle), 0, buffer, ctypes.byref(size)):
                name = buffer.value.lower()
            kernel.CloseHandle(wintypes.HANDLE(handle))
        names[pid.value] = name
    return names[pid.value]


def app_name(hwnd, names):
    """Store apps live inside an ApplicationFrameHost window; the app is its child's process."""
    name = process_path(hwnd, names)
    if not name.endswith("\\applicationframehost.exe"):
        return name
    user = ctypes.WinDLL("user32")
    found = []
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    @callback_type
    def child(handle, data):
        other = process_path(handle, names)
        if other != name:
            found.append(other)
            return False
        return True

    user.EnumChildWindows(wintypes.HWND(hwnd), child, 0)
    return found[0] if found else name


def window_stack(masked):
    """(masked, rect) of each window on screen, front to back, in physical screen pixels.

    A window that may be see-through (layered, click-through, or a topmost tool
    window such as an overlay) never counts as covering, so whatever is behind
    it stays black rather than leaking.
    """
    user, dwm = ctypes.WinDLL("user32"), ctypes.WinDLL("dwmapi")
    user.GetWindowLongW.restype = ctypes.c_long
    stack, names = [], {}
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    @callback_type
    def collect(hwnd, data):
        if not user.IsWindowVisible(hwnd) or user.IsIconic(hwnd):
            return True
        cloaked = wintypes.DWORD()
        dwm.DwmGetWindowAttribute(hwnd, 14, ctypes.byref(cloaked), 4)  # DWMWA_CLOAKED
        rect = wintypes.RECT()
        if cloaked.value or dwm.DwmGetWindowAttribute(hwnd, 9, ctypes.byref(rect), ctypes.sizeof(rect)):  # DWMWA_EXTENDED_FRAME_BOUNDS
            return True
        box = (rect.left, rect.top, rect.right, rect.bottom)
        if box[0] >= box[2] or box[1] >= box[3]:
            return True
        cls = ctypes.create_unicode_buffer(256)
        user.GetClassNameW(hwnd, cls, 256)
        if cls.value == SWITCHER_CLASS and process_path(hwnd, names).endswith("\\explorer.exe"):
            stack.append((True, box))
            return True
        if masked and matches(app_name(hwnd, names), masked):
            stack.append((True, box))
            return True
        style = user.GetWindowLongW(hwnd, -20) & 0xFFFFFFFF  # GWL_EXSTYLE
        if style & (WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE) or \
                style & (WS_EX_TOPMOST | WS_EX_TOOLWINDOW) == WS_EX_TOPMOST | WS_EX_TOOLWINDOW:
            return True
        if not user.IsZoomed(hwnd):  # maximized windows are not rounded
            box = (box[0] + CORNER, box[1] + CORNER, box[2] - CORNER, box[3] - CORNER)
        if box[0] < box[2] and box[1] < box[3]:
            stack.append((False, box))
        return True

    if not user.EnumWindows(collect, 0):
        raise ctypes.WinError()
    return tuple(stack)


def subtract(rect, hole):
    left, top, right, bottom = rect
    hl, ht, hr, hb = hole
    if hl >= right or hr <= left or ht >= bottom or hb <= top:
        return [rect]
    parts = []
    if ht > top:
        parts.append((left, top, right, ht))
    if hb < bottom:
        parts.append((left, hb, right, bottom))
    middle = (max(top, ht), min(bottom, hb))
    if hl > left:
        parts.append((left, middle[0], hl, middle[1]))
    if hr < right:
        parts.append((hr, middle[0], right, middle[1]))
    return parts


def black_regions(stack):
    """Parts of masked windows that are on screen, given (masked, rect) from front to back."""
    regions = []
    for masked, rect in reversed(stack):
        if masked:
            regions.append(rect)
        else:
            regions = [part for region in regions for part in subtract(region, rect)]
    return regions


def paint(image, screen, regions):
    draw = ImageDraw.Draw(image)
    for left, top, right, bottom in regions:
        left, top = max(left, screen[0]) - screen[0], max(top, screen[1]) - screen[1]
        right, bottom = min(right, screen[2]) - screen[0], min(bottom, screen[3]) - screen[1]
        if left < right and top < bottom:
            draw.rectangle((left, top, right - 1, bottom - 1), fill="black")


def take(store, device_id, masked_apps=()):
    if not desktop_available():
        return 0
    masked = normalized(masked_apps)
    screens = monitors()
    # The window list is read just before and after grabbing. If anything moved in
    # between, grab once more; if it is still moving, black both positions.
    for attempt in range(2):
        before = window_stack(masked)
        images = [(name, rect, ImageGrab.grab(bbox=rect, all_screens=True).convert("RGB")) for name, rect in screens]
        after = window_stack(masked)
        if before == after:
            break
    regions = black_regions(before) + (black_regions(after) if before != after else [])
    capture_id = uuid.uuid4().hex
    taken_at = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    count = 0
    for name, rect, image in images:
        paint(image, rect, regions)
        data = io.BytesIO()
        image.save(data, format="PNG")
        store.add(taken_at, dict(capture_id=capture_id, monitor_id=name,
                                width=image.width, height=image.height, device_id=device_id), data.getvalue())
        count += 1
    return count
