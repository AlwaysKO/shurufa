"""预制 GIF 首帧与 WebP 的可见像素质量门禁回归。"""
import sys
import unittest
from PIL import Image
sys.dont_write_bytecode = True
from expression_image_compare import same_visible_rgba


class VisibleRgbaTest(unittest.TestCase):
    def compare(self, first, second):
        return same_visible_rgba(Image.new('RGBA', (1, 1), first), Image.new('RGBA', (1, 1), second))

    def test_transparent_palette_rgb_is_not_visible(self):
        self.assertTrue(self.compare((76, 105, 113, 0), (0, 0, 0, 0)))

    def test_visible_rgb_difference_is_rejected(self):
        self.assertFalse(self.compare((76, 105, 113, 255), (75, 105, 113, 255)))

    def test_alpha_difference_is_rejected(self):
        self.assertFalse(self.compare((0, 0, 0, 0), (0, 0, 0, 1)))

    def test_partially_transparent_rgb_difference_is_rejected(self):
        self.assertFalse(self.compare((1, 0, 0, 1), (0, 0, 0, 1)))

    def test_identical_visible_pixels_are_accepted(self):
        self.assertTrue(self.compare((76, 105, 113, 128), (76, 105, 113, 128)))

    def test_different_dimensions_are_rejected(self):
        self.assertFalse(same_visible_rgba(Image.new('RGBA', (1, 1)), Image.new('RGBA', (2, 1))))


if __name__ == '__main__':
    unittest.main()
