"""Windows.Media.Ocr, Japanese, original resolution; tile oversized displays."""
import io
from PIL import Image

# The vision model is billed by the image it reads, not by the answer:
# 1920x1080 cost 65.9 neurons and 960x540 cost 32.5 on the same frames. The
# text is read here at full size, so the caption only has to say what is on
# screen, and 960 on the long edge is enough for that. At 640 the model began
# inventing titles it could no longer read.
CAPTION_EDGE = 960


def caption_copy(image, edge=CAPTION_EDGE):
    """A JPEG no larger than `edge` on its long side, for the vision model only."""
    small = image.copy()
    small.thumbnail((edge, edge), Image.LANCZOS)
    data = io.BytesIO()
    small.save(data, format="JPEG", quality=85)
    return data.getvalue()


async def recognize(png):
    from winrt.windows.globalization import Language
    from winrt.windows.media.ocr import OcrEngine
    from winrt.windows.graphics.imaging import BitmapDecoder, BitmapPixelFormat, BitmapAlphaMode
    from winrt.windows.storage.streams import InMemoryRandomAccessStream, DataWriter

    engine = OcrEngine.try_create_from_language(Language("ja"))
    if engine is None:
        raise RuntimeError("日本語OCRがありません。Windowsの日本語言語機能をインストールしてください")
    original = Image.open(io.BytesIO(png)).convert("RGB")
    maximum = OcrEngine.max_image_dimension
    texts = []
    for top in range(0, original.height, maximum):
        for left in range(0, original.width, maximum):
            tile = original.crop((left, top, min(left + maximum, original.width), min(top + maximum, original.height)))
            encoded = io.BytesIO()
            tile.save(encoded, format="PNG")
            stream = InMemoryRandomAccessStream()
            writer = DataWriter(stream)
            bitmap = None
            try:
                writer.write_bytes(encoded.getvalue())
                await writer.store_async()
                writer.detach_stream()
                stream.seek(0)
                decoder = await BitmapDecoder.create_async(stream)
                bitmap = await decoder.get_software_bitmap_converted_async(BitmapPixelFormat.BGRA8, BitmapAlphaMode.PREMULTIPLIED)
                result = await engine.recognize_async(bitmap)
                texts.extend(line.text for line in result.lines)
            finally:
                if bitmap is not None:
                    bitmap.close()
                writer.close()
                stream.close()
    text = "\n".join(texts)
    if len(text.encode("utf-8")) > 128 * 1024:
        raise RuntimeError("OCR結果が128KiBを超えました。記録は未送信キューに保持しています")
    jpeg = io.BytesIO()
    original.save(jpeg, format="JPEG", quality=90, subsampling=0)
    return text, jpeg.getvalue(), caption_copy(original)
