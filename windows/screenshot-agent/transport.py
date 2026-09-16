"""No redirect following and no deletion until a matching server receipt."""
import hashlib
import json
from urllib import request, parse, error
import uuid


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def multipart(jpeg, metadata, caption_jpeg=None):
    boundary = "lifelog-" + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'.encode()
            + json.dumps(metadata, ensure_ascii=False).encode("utf-8")
            + f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="screen.jpg"\r\nContent-Type: image/jpeg\r\n\r\n'.encode()
            + jpeg)
    # The smaller copy the server captions from. Rows from before it existed
    # have none, and the server falls back to the original.
    if caption_jpeg is not None:
        body += (f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="caption_image"; filename="caption.jpg"\r\nContent-Type: image/jpeg\r\n\r\n'.encode()
                 + caption_jpeg)
    body += f'\r\n--{boundary}--\r\n'.encode()
    if len(body) > 20 * 1024 * 1024:
        raise RuntimeError("画像が送信上限20MiBを超えています")
    return body, "multipart/form-data; boundary=" + boundary


def upload(config, row, opener=None):
    metadata = json.loads(row["metadata"])
    metadata["ocr_text"] = row["ocr_text"]
    body, content_type = multipart(row["jpeg"], metadata, row["caption_jpeg"])
    query = parse.urlencode(dict(source="desktop", taken_at=row["taken_at"],
                                 device_id=metadata["device_id"], sha256=hashlib.sha256(row["jpeg"]).hexdigest()))
    headers = {"Authorization": "Bearer " + config["token"], "Content-Type": content_type}
    if config["access_id"]:
        headers.update({"CF-Access-Client-Id": config["access_id"], "CF-Access-Client-Secret": config["access_secret"]})
    opener = opener or request.build_opener(NoRedirect())
    # Old Workers treat any unknown source as r1 and the multipart body as a
    # JPEG. Negotiate BEFORE sending bytes, not just when reading the receipt.
    health = request.Request(config["endpoint"].rstrip("/") + "/health",
                             headers={k: v for k, v in headers.items() if k != "Content-Type"})
    req = request.Request(config["endpoint"].rstrip("/") + "/v1/photos/" + row["id"] + "?" + query,
                          data=body, headers=headers, method="PUT")
    try:
        with opener.open(health, timeout=20) as response:
            if response.status != 200 or json.loads(response.read(65536)).get("desktop_upload") != 1:
                raise RuntimeError("サーバがPC画像に未対応です。0019とWorkerの更新後に再送します")
        with opener.open(req, timeout=20) as response:
            if not 200 <= response.status < 300:
                raise RuntimeError("送信HTTP " + str(response.status))
            receipt = json.loads(response.read(65536))
    except error.HTTPError as exc:
        raise RuntimeError("送信HTTP " + str(exc.code) + "（接続先・認証・サーバを確認してください）") from None
    except (error.URLError, TimeoutError):
        raise RuntimeError("サーバに接続できません。再送待ちです") from None
    if receipt.get("photo_id") != row["id"] or receipt.get("source") != "desktop":
        raise RuntimeError("サーバがPC画像の保存を確認できません。0019とWorkerの更新を確認してください")
    return receipt
