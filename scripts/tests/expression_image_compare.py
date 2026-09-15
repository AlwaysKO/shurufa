"""预制素材首帧比较，仅忽略完全透明像素中的隐藏 RGB。"""
from PIL import Image


def same_visible_rgba(first: Image.Image, second: Image.Image) -> bool:
    if first.size != second.size:
        return False
    left = first.convert('RGBA').tobytes()
    right = second.convert('RGBA').tobytes()
    return all(
        left[i + 3] == right[i + 3]
        and (left[i + 3] == 0 or left[i:i + 3] == right[i:i + 3])
        for i in range(0, len(left), 4)
    )
