import struct
import unittest

from build_input_completion_index import completion_rows, encode_index, parse_readings


class InputCompletionIndexTest(unittest.TestCase):
    def test_only_three_syllable_whole_words_get_initial_codes(self):
        rows = completion_rows([('就可以', 'jiu ke yi', 100), ('良口镇', 'liang kou zhen', 0),
                                ('美国最高法院', 'mei guo zui gao fa yuan', 1000)], set())
        self.assertEqual(['就可以', '良口镇'], [r[1] for r in rows if r[0] == 'a559'])
        self.assertFalse(any(r[0] == 'a649439' for r in rows))

    def test_only_curated_long_phrases_get_letter_and_numeric_prefix_indexes(self):
        rows = completion_rows([('飞流直下三千尺', 'fei liu zhi xia san qian chi', 20),
                                ('第二届中国', 'di er jie zhong guo', 1000)], {'飞流直下三千尺'})
        self.assertEqual({'n3345489449427267426244', 'pfeiliuzhixiasanqianchi'}, {r[0] for r in rows})

    def test_parse_rejects_unknown_readings_and_mismatched_syllables(self):
        text = '---\n...\n就可以\tjiu ke yi\t100\n良口镇\t100\n错误\tcuo\t10\n脏读音\ta ! a\t10\n'
        self.assertEqual([('就可以', 'jiu ke yi', 100)], parse_readings(text))

    def test_binary_offsets_are_complete_and_deterministic(self):
        rows = completion_rows([('就可以', 'jiu ke yi', 100)], set())
        data = encode_index(rows)
        self.assertEqual(b'T9COMP1\0', data[:8])
        self.assertEqual(1, struct.unpack_from('<I', data, 8)[0])
        self.assertEqual(0, struct.unpack_from('<I', data, 12)[0])
        self.assertEqual(len(data)-20, struct.unpack_from('<I', data, 16)[0])
        self.assertEqual(data, encode_index(rows))


if __name__ == '__main__':
    unittest.main()
