import unittest
import numpy as np
from preprocess import prepare
from calibrate import metrics


class ToolTests(unittest.TestCase):
    def test_portrait_preserves_top_bottom_and_padding_is_neutral(self):
        rgb = np.zeros((10, 2, 3), dtype=np.uint8)
        rgb[0] = [255, 0, 0]
        rgb[-1] = [0, 0, 255]
        result = prepare(rgb)[0]
        np.testing.assert_array_equal(result[0, 192], [255, 0, 0])
        np.testing.assert_array_equal(result[-1, 192], [0, 0, 255])
        np.testing.assert_array_equal(result[:, 0], np.full((384, 3), 128))

    def test_single_pixel_rgb_is_not_swapped(self):
        result = prepare(np.array([[[10, 20, 30]]], dtype=np.uint8))
        self.assertEqual(result.shape, (1, 384, 384, 3))
        np.testing.assert_array_equal(result[0, 200, 200], [10, 20, 30])

    def test_pair_metric_counts_and_threshold_boundary(self):
        result = metrics(np.array([.1, .5, .8, .9]), np.array([0, 0, 1, 1]), .5)
        self.assertEqual((result['tp'], result['fp'], result['fn'], result['tn']), (2, 1, 0, 1))
        self.assertEqual(result['recall'], 1)
        self.assertEqual(result['falsePositiveRate'], .5)


if __name__ == '__main__':
    unittest.main()
